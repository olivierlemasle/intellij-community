/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.psi.impl.file.impl;

import com.google.common.annotations.VisibleForTesting;
import com.intellij.injected.editor.VirtualFileWindow;
import com.intellij.lang.Language;
import com.intellij.lang.LanguageUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.roots.FileIndexFacade;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.LowMemoryWatcher;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.intellij.psi.*;
import com.intellij.psi.impl.DebugUtil;
import com.intellij.psi.impl.PsiManagerImpl;
import com.intellij.psi.impl.PsiModificationTrackerImpl;
import com.intellij.psi.impl.PsiTreeChangeEventImpl;
import com.intellij.psi.impl.file.PsiDirectoryFactory;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.util.ConcurrencyUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.messages.MessageBusConnection;
import gnu.trove.THashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

public class FileManagerImpl implements FileManager {
  private static final Logger LOG = Logger.getInstance("#com.intellij.psi.impl.file.impl.FileManagerImpl");
  private final Key<FileViewProvider> myPsiHardRefKey = Key.create("HARD_REFERENCE_TO_PSI"); //non-static!

  private final PsiManagerImpl myManager;
  private final FileIndexFacade myFileIndex;

  private final AtomicReference<ConcurrentMap<VirtualFile, PsiDirectory>> myVFileToPsiDirMap = new AtomicReference<>();
  private final AtomicReference<ConcurrentMap<VirtualFile, FileViewProvider>> myVFileToViewProviderMap = new AtomicReference<>();

  private boolean myDisposed;

  private final FileDocumentManager myFileDocumentManager;
  private final MessageBusConnection myConnection;

  public FileManagerImpl(PsiManagerImpl manager, FileDocumentManager fileDocumentManager, FileIndexFacade fileIndex) {
    myManager = manager;
    myFileIndex = fileIndex;
    myConnection = manager.getProject().getMessageBus().connect();

    myFileDocumentManager = fileDocumentManager;

    Disposer.register(manager.getProject(), this);
    LowMemoryWatcher.register(this::processQueue, this);

    myConnection.subscribe(DumbService.DUMB_MODE, new DumbService.DumbModeListener() {
      @Override
      public void enteredDumbMode() {
        processFileTypesChanged();
      }

      @Override
      public void exitDumbMode() {
        processFileTypesChanged();
      }
    });
  }

  private static final VirtualFile NULL = new LightVirtualFile();

  public void processQueue() {
    // just to call processQueue()
    ConcurrentMap<VirtualFile, FileViewProvider> map = myVFileToViewProviderMap.get();
    if (map != null) {
      map.remove(NULL);
    }
  }

  @VisibleForTesting
  @NotNull
  public ConcurrentMap<VirtualFile, FileViewProvider> getVFileToViewProviderMap() {
    ConcurrentMap<VirtualFile, FileViewProvider> map = myVFileToViewProviderMap.get();
    if (map == null) {
      map = ConcurrencyUtil.cacheOrGet(myVFileToViewProviderMap, ContainerUtil.createConcurrentWeakValueMap());
    }
    return map;
  }

  @NotNull
  private ConcurrentMap<VirtualFile, PsiDirectory> getVFileToPsiDirMap() {
    ConcurrentMap<VirtualFile, PsiDirectory> map = myVFileToPsiDirMap.get();
    if (map == null) {
      map = ConcurrencyUtil.cacheOrGet(myVFileToPsiDirMap, ContainerUtil.createConcurrentSoftValueMap());
    }
    return map;
  }

  public static void clearPsiCaches(@NotNull FileViewProvider provider) {
    ((AbstractFileViewProvider)provider).getCachedPsiFiles().forEach(PsiFile::clearCaches);
  }

  public void forceReload(@NotNull VirtualFile vFile) {
    LanguageSubstitutors.cancelReparsing(vFile);
    FileViewProvider viewProvider = findCachedViewProvider(vFile);
    if (viewProvider == null) {
      return;
    }
    ApplicationManager.getApplication().assertWriteAccessAllowed();

    VirtualFile dir = vFile.getParent();
    PsiDirectory parentDir = dir == null ? null : getCachedDirectory(dir);
    PsiTreeChangeEventImpl event = new PsiTreeChangeEventImpl(myManager);
    if (parentDir == null) {
      event.setPropertyName(PsiTreeChangeEvent.PROP_UNLOADED_PSI);

      myManager.beforePropertyChange(event);
      setViewProvider(vFile, null);
      myManager.propertyChanged(event);
    } else {
      event.setParent(parentDir);

      myManager.beforeChildrenChange(event);
      setViewProvider(vFile, null);
      myManager.childrenChanged(event);
    }
  }

  public void firePropertyChangedForUnloadedPsi() {
    PsiTreeChangeEventImpl event = new PsiTreeChangeEventImpl(myManager);
    event.setPropertyName(PsiTreeChangeEvent.PROP_UNLOADED_PSI);

    myManager.beforePropertyChange(event);
    myManager.propertyChanged(event);
  }

  @Override
  public void dispose() {
    myConnection.disconnect();
    clearViewProviders();

    myDisposed = true;
  }

  private void clearViewProviders() {
    ApplicationManager.getApplication().assertWriteAccessAllowed();
    DebugUtil.performPsiModification("clearViewProviders", () -> {
      ConcurrentMap<VirtualFile, FileViewProvider> map = myVFileToViewProviderMap.get();
      if (map != null) {
        for (final FileViewProvider provider : map.values()) {
          markInvalidated(provider);
        }
      }
      myVFileToViewProviderMap.set(null);
    });
  }

  @Override
  @TestOnly
  public void cleanupForNextTest() {
    ApplicationManager.getApplication().runWriteAction(this::clearViewProviders);

    myVFileToPsiDirMap.set(null);
    ((PsiModificationTrackerImpl)myManager.getModificationTracker()).incCounter();
  }

  @Override
  @NotNull
  public FileViewProvider findViewProvider(@NotNull final VirtualFile file) {
    assert !file.isDirectory();
    FileViewProvider viewProvider = findCachedViewProvider(file);
    if (viewProvider != null) return viewProvider;
    if (file instanceof VirtualFileWindow) {
      throw new IllegalStateException("File " + file + " is invalid");
    }
    viewProvider = createFileViewProvider(file, true);
    if (file instanceof LightVirtualFile) {
      return file.putUserDataIfAbsent(myPsiHardRefKey, viewProvider);
    }
    return ConcurrencyUtil.cacheOrGet(getVFileToViewProviderMap(), file, viewProvider);
  }

  @Override
  public FileViewProvider findCachedViewProvider(@NotNull final VirtualFile file) {
    ConcurrentMap<VirtualFile, FileViewProvider> map = myVFileToViewProviderMap.get();
    FileViewProvider viewProvider = map == null ? null : map.get(file);
    if (viewProvider == null) viewProvider = file.getUserData(myPsiHardRefKey);
    return viewProvider;
  }

  @Override
  public void setViewProvider(@NotNull final VirtualFile virtualFile, @Nullable final FileViewProvider fileViewProvider) {
    FileViewProvider prev = findCachedViewProvider(virtualFile);
    if (prev == fileViewProvider) return;
    if (prev != null) {
      DebugUtil.performPsiModification(null, () -> {
        markInvalidated(prev);
        DebugUtil.onInvalidated(prev);
      });
    }

    if (fileViewProvider == null) {
      getVFileToViewProviderMap().remove(virtualFile);
    }
    else if (virtualFile instanceof LightVirtualFile) {
      virtualFile.putUserData(myPsiHardRefKey, fileViewProvider);
    }
    else {
      getVFileToViewProviderMap().put(virtualFile, fileViewProvider);
    }
  }

  @Override
  @NotNull
  public FileViewProvider createFileViewProvider(@NotNull final VirtualFile file, boolean eventSystemEnabled) {
    FileType fileType = file.getFileType();
    Language language = LanguageUtil.getLanguageForPsi(myManager.getProject(), file);
    FileViewProviderFactory factory = language == null
                                      ? FileTypeFileViewProviders.INSTANCE.forFileType(fileType)
                                      : LanguageFileViewProviders.INSTANCE.forLanguage(language);
    FileViewProvider viewProvider = factory == null ? null : factory.createFileViewProvider(file, language, myManager, eventSystemEnabled);

    return viewProvider == null ? new SingleRootFileViewProvider(myManager, file, eventSystemEnabled, fileType) : viewProvider;
  }

  /** Left for plugin compatibility */
  @SuppressWarnings("unused")
  @Deprecated
  public void markInitialized() {
  }

  /** Left for plugin compatibility */
  @SuppressWarnings("unused")
  @Deprecated
  public boolean isInitialized() {
    return true;
  }

  private boolean myProcessingFileTypesChange;

  void processFileTypesChanged() {
    if (myProcessingFileTypesChange) return;
    myProcessingFileTypesChange = true;
    DebugUtil.performPsiModification(null, () -> {
      try {
        ApplicationManager.getApplication().runWriteAction(() -> {
          PsiTreeChangeEventImpl event = new PsiTreeChangeEventImpl(myManager);
          event.setPropertyName(PsiTreeChangeEvent.PROP_FILE_TYPES);
          myManager.beforePropertyChange(event);

          invalidateAllPsi();

          myManager.propertyChanged(event);
        });
      }
      finally {
        myProcessingFileTypesChange = false;
      }
    });
  }

  void invalidateAllPsi() {
    myVFileToPsiDirMap.set(null);
    for (final FileViewProvider provider : getVFileToViewProviderMap().values()) {
      markInvalidated(provider);
    }
    myVFileToViewProviderMap.set(null);
  }

  void dispatchPendingEvents() {
    if (myDisposed) {
      LOG.error("Project is already disposed: "+myManager.getProject());
    }

    myConnection.deliverImmediately();
  }

  @TestOnly
  public void checkConsistency() {
    Map<VirtualFile, FileViewProvider> fileToViewProvider = new HashMap<>(getVFileToViewProviderMap());
    myVFileToViewProviderMap.set(null);
    for (Map.Entry<VirtualFile, FileViewProvider> entry : fileToViewProvider.entrySet()) {
      final FileViewProvider fileViewProvider = entry.getValue();
      VirtualFile vFile = entry.getKey();
      LOG.assertTrue(vFile.isValid());
      PsiFile psiFile1 = findFile(vFile);
      if (psiFile1 != null && fileViewProvider != null && fileViewProvider.isPhysical()) { // might get collected
        PsiFile psi = fileViewProvider.getPsi(fileViewProvider.getBaseLanguage());
        assert psi != null : fileViewProvider +"; "+fileViewProvider.getBaseLanguage()+"; "+psiFile1;
        assert psiFile1.getClass().equals(psi.getClass()) : psiFile1 +"; "+psi + "; "+psiFile1.getClass() +"; "+psi.getClass();
      }
    }

    Map<VirtualFile, PsiDirectory> fileToPsiDirMap = new HashMap<>(getVFileToPsiDirMap());
    myVFileToPsiDirMap.set(null);

    for (VirtualFile vFile : fileToPsiDirMap.keySet()) {
      LOG.assertTrue(vFile.isValid());
      PsiDirectory psiDir1 = findDirectory(vFile);
      LOG.assertTrue(psiDir1 != null);

      VirtualFile parent = vFile.getParent();
      if (parent != null) {
        LOG.assertTrue(getVFileToPsiDirMap().get(parent) != null);
      }
    }
  }

  @Override
  @Nullable
  public PsiFile findFile(@NotNull VirtualFile vFile) {
    if (vFile.isDirectory()) return null;

    ApplicationManager.getApplication().assertReadAccessAllowed();
    if (!vFile.isValid()) {
      LOG.error("Invalid file: " + vFile);
      return null;
    }

    dispatchPendingEvents();
    final FileViewProvider viewProvider = findViewProvider(vFile);
    return viewProvider.getPsi(viewProvider.getBaseLanguage());
  }

  @Override
  @Nullable
  public PsiFile getCachedPsiFile(@NotNull VirtualFile vFile) {
    ApplicationManager.getApplication().assertReadAccessAllowed();
    LOG.assertTrue(vFile.isValid(), "Invalid file");
    if (myDisposed) {
      LOG.error("Project is already disposed: " + myManager.getProject());
    }

    dispatchPendingEvents();

    return getCachedPsiFileInner(vFile);
  }

  @Override
  @Nullable
  public PsiDirectory findDirectory(@NotNull VirtualFile vFile) {
    if (myDisposed) {
      LOG.error("Access to psi files should not be performed after project disposal: "+myManager.getProject());
    }


    ApplicationManager.getApplication().assertReadAccessAllowed();
    if (!vFile.isValid()) {
      LOG.error("File is not valid:" + vFile);
      return null;
    }

    if (!vFile.isDirectory()) return null;
    dispatchPendingEvents();

    return findDirectoryImpl(vFile);
  }

  @Nullable
  private PsiDirectory findDirectoryImpl(@NotNull VirtualFile vFile) {
    PsiDirectory psiDir = getVFileToPsiDirMap().get(vFile);
    if (psiDir != null) return psiDir;

    if (Registry.is("ide.hide.excluded.files")) {
      if (myFileIndex.isExcludedFile(vFile)) return null;
    }
    else {
      if (myFileIndex.isUnderIgnored(vFile)) return null;
    }

    VirtualFile parent = vFile.getParent();
    if (parent != null) { //?
      findDirectoryImpl(parent);// need to cache parent directory - used for firing events
    }

    psiDir = PsiDirectoryFactory.getInstance(myManager.getProject()).createDirectory(vFile);
    return ConcurrencyUtil.cacheOrGet(getVFileToPsiDirMap(), vFile, psiDir);
  }

  public PsiDirectory getCachedDirectory(@NotNull VirtualFile vFile) {
    return getVFileToPsiDirMap().get(vFile);
  }

  void removeFilesAndDirsRecursively(@NotNull VirtualFile vFile) {
    DebugUtil.performPsiModification("removeFilesAndDirsRecursively", () -> {
      VfsUtilCore.visitChildrenRecursively(vFile, new VirtualFileVisitor() {
        @Override
        public boolean visitFile(@NotNull VirtualFile file) {
          if (file.isDirectory()) {
            getVFileToPsiDirMap().remove(file);
          }
          else {
            FileViewProvider viewProvider = getVFileToViewProviderMap().remove(file);
            if (viewProvider != null) {
              markInvalidated(viewProvider);
            }
          }
          return true;
        }
      });
    });
  }

  private void markInvalidated(@NotNull FileViewProvider viewProvider) {
    ((AbstractFileViewProvider)viewProvider).markInvalidated();
    viewProvider.getVirtualFile().putUserData(myPsiHardRefKey, null);
  }

  @Nullable
  PsiFile getCachedPsiFileInner(@NotNull VirtualFile file) {
    FileViewProvider fileViewProvider = findCachedViewProvider(file);
    return fileViewProvider != null ? ((AbstractFileViewProvider)fileViewProvider).getCachedPsi(fileViewProvider.getBaseLanguage()) : null;
  }

  @NotNull
  @Override
  public List<PsiFile> getAllCachedFiles() {
    List<PsiFile> files = new ArrayList<>();
    for (FileViewProvider provider : getVFileToViewProviderMap().values()) {
      ContainerUtil.addIfNotNull(files, ((AbstractFileViewProvider)provider).getCachedPsi(provider.getBaseLanguage()));
    }
    return files;
  }

  private void removeInvalidDirs(boolean useFind) {
    Map<VirtualFile, PsiDirectory> fileToPsiDirMap = new THashMap<>(getVFileToPsiDirMap());
    if (useFind) {
      myVFileToPsiDirMap.set(null);
    }
    for (Iterator<VirtualFile> iterator = fileToPsiDirMap.keySet().iterator(); iterator.hasNext();) {
      VirtualFile vFile = iterator.next();
      if (!vFile.isValid()) {
        iterator.remove();
      }
      else {
        PsiDirectory psiDir = findDirectory(vFile);
        if (psiDir == null) {
          iterator.remove();
        }
      }
    }
    myVFileToPsiDirMap.set(null);
    getVFileToPsiDirMap().putAll(fileToPsiDirMap);
  }

  void removeInvalidFilesAndDirs(boolean useFind) {
    removeInvalidDirs(useFind);

    // note: important to update directories map first - findFile uses findDirectory!
    Map<VirtualFile, FileViewProvider> fileToPsiFileMap = new THashMap<>(getVFileToViewProviderMap());
    Map<VirtualFile, FileViewProvider> originalFileToPsiFileMap = new THashMap<>(getVFileToViewProviderMap());
    if (useFind) {
      myVFileToViewProviderMap.set(null);
    }
    for (Iterator<VirtualFile> iterator = fileToPsiFileMap.keySet().iterator(); iterator.hasNext();) {
      VirtualFile vFile = iterator.next();

      if (!vFile.isValid()) {
        iterator.remove();
        continue;
      }

      if (useFind) {
        FileViewProvider view = fileToPsiFileMap.get(vFile);
        if (view == null) { // soft ref. collected
          iterator.remove();
          continue;
        }
        PsiFile psiFile1 = findFile(vFile);
        if (psiFile1 == null) {
          iterator.remove();
          continue;
        }

        if (!areViewProvidersEquivalent(view, psiFile1.getViewProvider())) {
          iterator.remove();
        }
        else {
          clearPsiCaches(view);
        }
      }
    }
    myVFileToViewProviderMap.set(null);
    getVFileToViewProviderMap().putAll(fileToPsiFileMap);

    markInvalidations(originalFileToPsiFileMap);
  }

  static boolean areViewProvidersEquivalent(@NotNull FileViewProvider view1, @NotNull FileViewProvider view2) {
    if (view1.getClass() != view2.getClass() || view1.getFileType() != view2.getFileType()) return false;

    Language baseLanguage = view1.getBaseLanguage();
    if (baseLanguage != view2.getBaseLanguage()) return false;

    if (!view1.getLanguages().equals(view2.getLanguages())) return false;
    PsiFile psi1 = view1.getPsi(baseLanguage);
    PsiFile psi2 = view2.getPsi(baseLanguage);
    if (psi1 == null) return psi2 == null;
    if (psi1.getClass() != psi2.getClass()) return false;

    return true;
  }

  private void markInvalidations(@NotNull Map<VirtualFile, FileViewProvider> originalFileToPsiFileMap) {
    DebugUtil.performPsiModification(null, ()->{
      for (Map.Entry<VirtualFile, FileViewProvider> entry : originalFileToPsiFileMap.entrySet()) {
        FileViewProvider viewProvider = entry.getValue();
        if (getVFileToViewProviderMap().get(entry.getKey()) != viewProvider) {
          markInvalidated(viewProvider);
        }
      }
    });
  }

  @Override
  public void reloadFromDisk(@NotNull PsiFile file) {
    ApplicationManager.getApplication().assertWriteAccessAllowed();
    VirtualFile vFile = file.getVirtualFile();
    assert vFile != null;

    if (file instanceof PsiBinaryFile) return;
    FileDocumentManager fileDocumentManager = myFileDocumentManager;
    Document document = fileDocumentManager.getCachedDocument(vFile);
    if (document != null) {
      fileDocumentManager.reloadFromDisk(document);
    }
    else {
      reloadPsiAfterTextChange(file, vFile);
    }
  }

  void reloadPsiAfterTextChange(@NotNull PsiFile file, @NotNull VirtualFile vFile) {
    FileViewProvider latestProvider = createFileViewProvider(vFile, false);
    PsiFile psi = latestProvider.getPsi(latestProvider.getBaseLanguage());
    if (psi instanceof PsiLargeFile || psi instanceof PsiBinaryFile) {
      forceReload(vFile);
      return;
    }

    ((AbstractFileViewProvider)file.getViewProvider()).onContentReload();
  }
}
