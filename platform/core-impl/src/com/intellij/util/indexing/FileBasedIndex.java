/*
 * Copyright 2000-2012 JetBrains s.r.o.
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

package com.intellij.util.indexing;

import com.intellij.AppTopics;
import com.intellij.openapi.application.ApplicationAdapter;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerAdapter;
import com.intellij.openapi.fileTypes.impl.FileTypeManagerImpl;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator;
import com.intellij.openapi.project.DumbModeAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.CollectingContentIterator;
import com.intellij.openapi.roots.ContentIterator;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.ex.VirtualFileManagerEx;
import com.intellij.openapi.vfs.newvfs.persistent.FlushingDaemon;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLock;
import com.intellij.psi.impl.PsiDocumentTransactionListener;
import com.intellij.psi.impl.source.PsiFileImpl;
import com.intellij.psi.search.EverythingGlobalScope;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.stubs.SerializationManager;
import com.intellij.util.*;
import com.intellij.util.concurrency.Semaphore;
import com.intellij.util.containers.ConcurrentHashSet;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.HashMap;
import com.intellij.util.containers.HashSet;
import com.intellij.util.io.*;
import com.intellij.util.io.DataOutputStream;
import com.intellij.util.io.storage.HeavyProcessLatch;
import com.intellij.util.messages.MessageBus;
import com.intellij.util.messages.MessageBusConnection;
import gnu.trove.*;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Eugene Zhuravlev
 *         Date: Dec 20, 2007
 */

public abstract class FileBasedIndex {
  private static final Logger LOG = Logger.getInstance("#com.intellij.util.indexing.FileBasedIndex");
  @NonNls
  private static final String CORRUPTION_MARKER_NAME = "corruption.marker";
  private final TObjectIntHashMap<ID<?, ?>> myIndexIdToVersionMap = new TObjectIntHashMap<ID<?, ?>>();
  private final Set<ID<?, ?>> myNotRequiringContentIndices = new THashSet<ID<?, ?>>();
  private final Set<ID<?, ?>> myRequiringContentIndices = new THashSet<ID<?, ?>>();

  private final ChangedFilesCollector myChangedFilesCollector;

  private final List<IndexableFileSet> myIndexableSets = ContainerUtil.createEmptyCOWList();
  private final Map<IndexableFileSet, Project> myIndexableSetToProjectMap = new THashMap<IndexableFileSet, Project>();

  private static final int OK = 1;
  private static final int REQUIRES_REBUILD = 2;
  private static final int REBUILD_IN_PROGRESS = 3;
  private static final Map<ID<?, ?>, AtomicInteger> ourRebuildStatus = new THashMap<ID<?,?>, AtomicInteger>();

  private final VirtualFileManagerEx myVfManager;
  private final FileDocumentManager myFileDocumentManager;
  private FileBasedIndexUnsavedDocumentsManager myUnsavedDocumentsManager;
  private FileBasedIndexIndicesManager myIndexIndicesManager;
  private FileBasedIndexTransactionMap myTransactionMap;
  private FileBasedIndexLimitsChecker myLimitsChecker;
  private final AbstractVfsAdapter myVfsAdapter;

  private static final int ALREADY_PROCESSED = 0x02;
  @Nullable private final String myConfigPath;
  @Nullable private final String mySystemPath;
  private final boolean myIsUnitTestMode;
  private ScheduledFuture<?> myFlushingFuture;
  private volatile int myLocalModCount;

  public void requestReindex(final VirtualFile file) {
    myChangedFilesCollector.invalidateIndices(file, true);
  }

  public void requestReindexExcluded(final VirtualFile file) {
    myChangedFilesCollector.invalidateIndices(file, false);
  }

  public FileBasedIndex(final VirtualFileManagerEx vfManager, FileDocumentManager fdm,
                        MessageBus bus,
                        FileBasedIndexUnsavedDocumentsManager unsavedDocumentsManager,
                        FileBasedIndexIndicesManager indexIndicesManager,
                        FileBasedIndexTransactionMap transactionMap,
                        FileBasedIndexLimitsChecker limitsChecker,
                        AbstractVfsAdapter vfsAdapter,
                        SerializationManager sm /*need this parameter to ensure component dependency*/) throws IOException {
    myVfManager = vfManager;
    myFileDocumentManager = fdm;
    myUnsavedDocumentsManager = unsavedDocumentsManager;
    myIndexIndicesManager = indexIndicesManager;
    myTransactionMap = transactionMap;
    myLimitsChecker = limitsChecker;
    myVfsAdapter = vfsAdapter;
    myIsUnitTestMode = ApplicationManager.getApplication().isUnitTestMode();
    myConfigPath = calcConfigPath(PathManager.getConfigPath());
    mySystemPath = calcConfigPath(PathManager.getSystemPath());

    final MessageBusConnection connection = bus.connect();
    connection.subscribe(PsiDocumentTransactionListener.TOPIC, new PsiDocumentTransactionListener() {
      @Override
      public void transactionStarted(final Document doc, final PsiFile file) {
        if (file != null) {
          synchronized (myTransactionMap) {
            myTransactionMap.put(doc, file);
          }
          myIndexIndicesManager.invalidateUpToDate();
        }
      }

      @Override
      public void transactionCompleted(final Document doc, final PsiFile file) {
        synchronized (myTransactionMap) {
          myTransactionMap.remove(doc);
        }
      }
    });

    connection.subscribe(AppTopics.FILE_DOCUMENT_SYNC, new FileDocumentManagerAdapter() {
      @Override
      public void fileContentReloaded(VirtualFile file, Document document) {
        cleanupMemoryStorage();
      }

      @Override
      public void unsavedDocumentsDropped() {
        cleanupMemoryStorage();
      }
    });

    ApplicationManager.getApplication().addApplicationListener(new ApplicationAdapter() {
      @Override
      public void writeActionStarted(Object action) {
        myIndexIndicesManager.invalidateUpToDate();
      }
    });

    myChangedFilesCollector = new ChangedFilesCollector();

    /*
    final File workInProgressFile = getMarkerFile();
    if (workInProgressFile.exists()) {
      // previous IDEA session was closed incorrectly, so drop all indices
      FileUtil.delete(PathManager.getIndexRoot());
    }
    */

    try {
      final FileBasedIndexExtension[] extensions = Extensions.getExtensions(FileBasedIndexExtension.EXTENSION_POINT_NAME);
      for (FileBasedIndexExtension<?, ?> extension : extensions) {
        ourRebuildStatus.put(extension.getName(), new AtomicInteger(OK));
      }

      final File corruptionMarker = new File(PathManager.getIndexRoot(), CORRUPTION_MARKER_NAME);
      final boolean currentVersionCorrupted = corruptionMarker.exists();
      boolean versionChanged = false;
      for (FileBasedIndexExtension<?, ?> extension : extensions) {
        versionChanged |= registerIndexer(extension, currentVersionCorrupted);
      }
      FileUtil.delete(corruptionMarker);

      String rebuildNotification = null;
      if (currentVersionCorrupted) {
        rebuildNotification = "Index files on disk are corrupted. Indices will be rebuilt.";
      }
      else if (versionChanged) {
        rebuildNotification = "Index file format has changed for some indices. These indices will be rebuilt.";
      }
      if (rebuildNotification != null
          && Registry.is("ide.showIndexRebuildMessage")) {
        notifyIndexRebuild(rebuildNotification);
      }

      dropUnregisteredIndices();

      // check if rebuild was requested for any index during registration
      for (ID<?, ?> indexId : myIndexIndicesManager.keySet()) {
        if (ourRebuildStatus.get(indexId).compareAndSet(REQUIRES_REBUILD, OK)) {
          try {
            clearIndex(indexId);
          }
          catch (StorageException e) {
            requestRebuild(indexId);
            LOG.error(e);
          }
        }
      }

      myVfManager.addVirtualFileListener(myChangedFilesCollector);

      IndexableFileSet additionalIndexableFileSet = myVfsAdapter.getAdditionalIndexableFileSet();
      if(additionalIndexableFileSet != null)
        registerIndexableSet(additionalIndexableFileSet, null);
    }
    finally {
      ShutDownTracker.getInstance().registerShutdownTask(new Runnable() {
        @Override
        public void run() {
          performShutdown();
        }
      });
      //FileUtil.createIfDoesntExist(workInProgressFile);
      saveRegisteredIndices(myIndexIndicesManager.keySet());
      myFlushingFuture = FlushingDaemon.everyFiveSeconds(new Runnable() {
        int lastModCount = 0;
        @Override
        public void run() {
          if (lastModCount == myLocalModCount) {
            flushAllIndices(lastModCount);
          }
          lastModCount = myLocalModCount;
        }
      });

    }
  }

  protected void notifyIndexRebuild(String rebuildNotification) {
  }

  private static String calcConfigPath(final String path) {
    try {
      final String _path = FileUtil.toSystemIndependentName(new File(path).getCanonicalPath());
      return _path.endsWith("/")? _path : _path + "/" ;
    }
    catch (IOException e) {
      LOG.info(e);
      return null;
    }
  }

  private static class FileBasedIndexHolder {
    private static final FileBasedIndex ourInstance = ApplicationManager.getApplication().getComponent(FileBasedIndex.class);
  }

  public static FileBasedIndex getInstance() {
    return FileBasedIndexHolder.ourInstance;
  }

  /**
   * @return true if registered index requires full rebuild for some reason, e.g. is just created or corrupted
   *
   * @param extension
   * @param isCurrentVersionCorrupted
   */
  private <K, V> boolean registerIndexer(final FileBasedIndexExtension<K, V> extension, final boolean isCurrentVersionCorrupted) throws IOException {
    final ID<K, V> name = extension.getName();
    final int version = extension.getVersion();
    final File versionFile = IndexInfrastructure.getVersionFile(name);
    final boolean versionFileExisted = versionFile.exists();
    boolean versionChanged = false;
    if (isCurrentVersionCorrupted || IndexInfrastructure.versionDiffers(versionFile, version)) {
      if (!isCurrentVersionCorrupted && versionFileExisted) {
        versionChanged = true;
        LOG.info("Version has changed for index " + extension.getName() + ". The index will be rebuilt.");
      }
      FileUtil.delete(IndexInfrastructure.getIndexRootDir(name));
      IndexInfrastructure.rewriteVersion(versionFile, version);
    }

    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        final MapIndexStorage<K, V> storage = new MapIndexStorage<K, V>(IndexInfrastructure.getStorageFile(name), extension.getKeyDescriptor(), extension.getValueExternalizer(), extension.getCacheSize());
        final MemoryIndexStorage<K, V> memStorage = new MemoryIndexStorage<K, V>(storage);
        final UpdatableIndex<K, V, FileContent> index = createIndex(name, extension, memStorage);
        final FileBasedIndexIndicesManager.InputFilter inputFilter = extension.getInputFilter();
        
        assert inputFilter != null : "Index extension " + name + " must provide non-null input filter";

        myIndexIndicesManager.addNewIndex(name,
                                    new Pair<UpdatableIndex<?, ?, FileContent>, FileBasedIndexIndicesManager.InputFilter>(index, new IndexableFilesFilter(inputFilter)));
        myIndexIdToVersionMap.put(name, version);
        if (!extension.dependsOnFileContent()) {
          myNotRequiringContentIndices.add(name);
        }
        else {
          myRequiringContentIndices.add(name);
        }
        myLimitsChecker.addNoLimitsFileTypes(extension.getFileTypesWithSizeLimitNotApplicable());
        break;
      }
      catch (IOException e) {
        LOG.info(e);
        FileUtil.delete(IndexInfrastructure.getIndexRootDir(name));
        IndexInfrastructure.rewriteVersion(versionFile, version);
      }
    }
    return versionChanged;
  }

  private static void saveRegisteredIndices(Collection<ID<?, ?>> ids) {
    final File file = getRegisteredIndicesFile();
    try {
      FileUtil.createIfDoesntExist(file);
      final DataOutputStream os = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)));
      try {
        os.writeInt(ids.size());
        for (ID<?, ?> id : ids) {
          IOUtil.writeString(id.toString(), os);
        }
      }
      finally {
        os.close();
      }
    }
    catch (IOException ignored) {
    }
  }

  private static Set<String> readRegisteredIndexNames() {
    final Set<String> result = new HashSet<String>();
    try {
      final DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(getRegisteredIndicesFile())));
      try {
        final int size = in.readInt();
        for (int idx = 0; idx < size; idx++) {
          result.add(IOUtil.readString(in));
        }
      }
      finally {
        in.close();
      }
    }
    catch (IOException ignored) {
    }
    return result;
  }

  private static File getRegisteredIndicesFile() {
    return new File(PathManager.getIndexRoot(), "registered");
  }

  private <K, V> UpdatableIndex<K, V, FileContent> createIndex(final ID<K, V> indexId, final FileBasedIndexExtension<K, V> extension, final MemoryIndexStorage<K, V> storage) throws IOException {
    final MapReduceIndex<K, V, FileContent> index;
    if (extension instanceof CustomImplementationFileBasedIndexExtension) {
      final UpdatableIndex<K, V, FileContent> custom = ((CustomImplementationFileBasedIndexExtension<K, V, FileContent>)extension).createIndexImplementation(indexId, this, storage);
      
      assert custom != null : "Custom index implementation must not be null; index: " + indexId;
      
      if (!(custom instanceof MapReduceIndex)) {
        return custom;
      }
      index = (MapReduceIndex<K,V, FileContent>)custom;
    }
    else {
      index = new MapReduceIndex<K, V, FileContent>(indexId, extension.getIndexer(), storage);
    }

    final KeyDescriptor<K> keyDescriptor = extension.getKeyDescriptor();
    index.setInputIdToDataKeysIndex(new Factory<PersistentHashMap<Integer, Collection<K>>>() {
      @Override
      public PersistentHashMap<Integer, Collection<K>> create() {
        try {
          return createIdToDataKeysIndex(indexId, keyDescriptor, storage);
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    });

    return index;
  }

  private static <K> PersistentHashMap<Integer, Collection<K>> createIdToDataKeysIndex(final ID<K, ?> indexId,
                                                                                       final KeyDescriptor<K> keyDescriptor,
                                                                                       MemoryIndexStorage<K, ?> storage) throws IOException {
    final File indexStorageFile = IndexInfrastructure.getInputIndexStorageFile(indexId);
    final Ref<Boolean> isBufferingMode = new Ref<Boolean>(false);
    final Map<Integer, Collection<K>> tempMap = new HashMap<Integer, Collection<K>>();

    final DataExternalizer<Collection<K>> dataExternalizer = new DataExternalizer<Collection<K>>() {
      @Override
      public void save(DataOutput out, Collection<K> value) throws IOException {
        try {
          DataInputOutputUtil.writeINT(out, value.size());
          for (K key : value) {
            keyDescriptor.save(out, key);
          }
        }
        catch (IllegalArgumentException e) {
          throw new IOException("Error saving data for index " + indexId, e);
        }
      }

      @Override
      public Collection<K> read(DataInput in) throws IOException {
        try {
          final int size = DataInputOutputUtil.readINT(in);
          final List<K> list = new ArrayList<K>(size);
          for (int idx = 0; idx < size; idx++) {
            list.add(keyDescriptor.read(in));
          }
          return list;
        }
        catch (IllegalArgumentException e) {
          throw new IOException("Error reading data for index " + indexId, e);
        }
      }
    };
    
    // Important! Update IdToDataKeysIndex depending on the sate of "buffering" flag from the MemoryStorage.
    // If buffering is on, all changes should be done in memory (similar to the way it is done in memory storage).
    // Otherwise data in IdToDataKeysIndex will not be in sync with the 'main' data in the index on disk and index updates will be based on the
    // wrong sets of keys for the given file. This will lead to unpredictable results in main index because it will not be
    // cleared properly before updating (removed data will still be present on disk). See IDEA-52223 for illustration of possible effects.

    final PersistentHashMap<Integer, Collection<K>> map = new PersistentHashMap<Integer, Collection<K>>(
      indexStorageFile, new EnumeratorIntegerDescriptor(), dataExternalizer
    ) {

      @Override
      protected Collection<K> doGet(Integer integer) throws IOException {
        if (isBufferingMode.get()) {
          final Collection<K> collection = tempMap.get(integer);
          if (collection != null) {
            return collection;
          }
        }
        return super.doGet(integer);
      }

      @Override
      protected void doPut(Integer integer, Collection<K> ks) throws IOException {
        if (isBufferingMode.get()) {
          tempMap.put(integer, ks == null? Collections.<K>emptySet() : ks);
        }
        else {
          super.doPut(integer, ks);
        }
      }

      @Override
      protected void doRemove(Integer integer) throws IOException {
        if (isBufferingMode.get()) {
          tempMap.put(integer, Collections.<K>emptySet());
        }
        else {
          super.doRemove(integer);
        }
      }
    };

    storage.addBufferingStateListsner(new MemoryIndexStorage.BufferingStateListener() {
      @Override
      public void bufferingStateChanged(boolean newState) {
        synchronized (map) {
          isBufferingMode.set(newState);
        }
      }
      @Override
      public void memoryStorageCleared() {
        synchronized (map) {
          tempMap.clear();
        }
      }
    });
    return map;
  }

  public void disposeComponent() {
    performShutdown();
  }

  public void initComponent() {
  }

  private final AtomicBoolean myShutdownPerformed = new AtomicBoolean(false);

  private void performShutdown() {
    if (!myShutdownPerformed.compareAndSet(false, true)) {
      return; // already shut down
    }
    try {
      if (myFlushingFuture != null) {
        myFlushingFuture.cancel(false);
        myFlushingFuture = null;
      }

      myFileDocumentManager.saveAllDocuments();
    }
    finally {
      LOG.info("START INDEX SHUTDOWN");
      try {
        myChangedFilesCollector.forceUpdate(null, null, null, true);

        for (ID<?, ?> indexId : myIndexIndicesManager.keySet()) {
          final UpdatableIndex<?, ?, FileContent> index = myIndexIndicesManager.getIndex(indexId);
          assert index != null;
          checkRebuild(indexId, true); // if the index was scheduled for rebuild, only clean it
          //LOG.info("DISPOSING " + indexId);
          index.dispose();
        }

        myVfManager.removeVirtualFileListener(myChangedFilesCollector);

        //FileUtil.delete(getMarkerFile());
      }
      catch (Throwable e) {
        LOG.info("Problems during index shutdown", e);
        throw new RuntimeException(e);
      }
      LOG.info("END INDEX SHUTDOWN");
    }
  }

  private void flushAllIndices(final long modCount) {
    if (HeavyProcessLatch.INSTANCE.isRunning()) {
      return;
    }
    IndexingStamp.flushCache();
    for (ID<?, ?> indexId : new ArrayList<ID<?, ?>>(myIndexIndicesManager.keySet())) {
      if (HeavyProcessLatch.INSTANCE.isRunning() || modCount != myLocalModCount) {
        return; // do not interfere with 'main' jobs
      }
      try {
        final UpdatableIndex<?, ?, FileContent> index = myIndexIndicesManager.getIndex(indexId);
        if (index != null) {
          index.flush();
        }
      }
      catch (StorageException e) {
        LOG.info(e);
        requestRebuild(indexId);
      }
    }

    if (!HeavyProcessLatch.INSTANCE.isRunning() && modCount == myLocalModCount) { // do not interfere with 'main' jobs
      SerializationManager.getInstance().flushNameStorage();
    }
  }

  /**
   * @param project it is guaranteed to return data which is up-to-date withing the project
   * Keys obtained from the files which do not belong to the project specified may not be up-to-date or even exist
   */
  @NotNull
  public <K> Collection<K> getAllKeys(final ID<K, ?> indexId, @NotNull Project project) {
    Set<K> allKeys = new HashSet<K>();
    processAllKeys(indexId, new CommonProcessors.CollectProcessor<K>(allKeys), project);
    return allKeys;
  }

  /**
   * @param project it is guaranteed to return data which is up-to-date withing the project
   * Keys obtained from the files which do not belong to the project specified may not be up-to-date or even exist
   */
  public <K> boolean processAllKeys(final ID<K, ?> indexId, Processor<K> processor, @Nullable Project project) {
    try {
      final UpdatableIndex<K, ?, FileContent> index = myIndexIndicesManager.getIndex(indexId);
      if (index == null) {
        return true;
      }
      ensureUpToDate(indexId, project, project != null? GlobalSearchScope.allScope(project) : new EverythingGlobalScope());
      return index.processAllKeys(processor);
    }
    catch (StorageException e) {
      scheduleRebuild(indexId, e);
    }
    catch (RuntimeException e) {
      final Throwable cause = e.getCause();
      if (cause instanceof StorageException || cause instanceof IOException) {
        scheduleRebuild(indexId, cause);
      }
      else {
        throw e;
      }
    }

    return false;
  }

  private static final ThreadLocal<Integer> myUpToDateCheckState = new ThreadLocal<Integer>();

  public static void disableUpToDateCheckForCurrentThread() {
    final Integer currentValue = myUpToDateCheckState.get();
    myUpToDateCheckState.set(currentValue == null? 1 : currentValue.intValue() + 1);
  }

  public static void enableUpToDateCheckForCurrentThread() {
    final Integer currentValue = myUpToDateCheckState.get();
    if (currentValue != null) {
      final int newValue = currentValue.intValue() - 1;
      if (newValue != 0) {
        myUpToDateCheckState.set(newValue);
      }
      else {
        myUpToDateCheckState.remove();
      }
    }
  }

  private static boolean isUpToDateCheckEnabled() {
    final Integer value = myUpToDateCheckState.get();
    return value == null || value.intValue() == 0;
  }


  private final ThreadLocal<Boolean> myReentrancyGuard = new ThreadLocal<Boolean>() {
    @Override
    protected Boolean initialValue() {
      return Boolean.FALSE;
    }
  };

  /**
   * DO NOT CALL DIRECTLY IN CLIENT CODE
   * The method is internal to indexing engine end is called internally. The method is public due to implementation details
   */
  public <K> void ensureUpToDate(final ID<K, ?> indexId, @Nullable Project project, @Nullable GlobalSearchScope filter) {
    ensureUpToDate(indexId, project, filter, null);
  }

  private <K> void ensureUpToDate(final ID<K, ?> indexId, @Nullable Project project, @Nullable GlobalSearchScope filter,
                                 @Nullable VirtualFile restrictedFile) {
    if (!needsFileContentLoading(indexId)) {
      return; //indexed eagerly in foreground while building unindexed file list
    }
    if (isDumb(project)) {
      handleDumbMode(project);
    }

    if (myReentrancyGuard.get().booleanValue()) {
      //assert false : "ensureUpToDate() is not reentrant!";
      return;
    }
    myReentrancyGuard.set(Boolean.TRUE);

    try {
      myChangedFilesCollector.ensureAllInvalidateTasksCompleted();
      if (isUpToDateCheckEnabled()) {
        try {
          checkRebuild(indexId, false);
          myChangedFilesCollector.forceUpdate(project, filter, restrictedFile, false);
          myUnsavedDocumentsManager.indexUnsavedDocuments(indexId, project, filter, restrictedFile);
        }
        catch (StorageException e) {
          scheduleRebuild(indexId, e);
        }
        catch (RuntimeException e) {
          final Throwable cause = e.getCause();
          if (cause instanceof StorageException || cause instanceof IOException) {
            scheduleRebuild(indexId, e);
          }
          else {
            throw e;
          }
        }
      }
    }
    finally {
      myReentrancyGuard.set(Boolean.FALSE);
    }
  }

  private void handleDumbMode(@Nullable Project project) {
    checkCanceled(); // DumbModeAction.CANCEL

    if (project != null) {
      final ProgressIndicator progressIndicator = getProgressIndicator();
      if (progressIndicator instanceof BackgroundableProcessIndicator) {
        final BackgroundableProcessIndicator indicator = (BackgroundableProcessIndicator)progressIndicator;
        if (indicator.getDumbModeAction() == DumbModeAction.WAIT) {
          assert !ApplicationManager.getApplication().isDispatchThread();
          DumbService.getInstance(project).waitForSmartMode();
          return;
        }
      }
    }

    throw new IndexNotReadyException();
  }

  protected abstract boolean isDumb(@Nullable Project project);

  @NotNull
  public <K, V> List<V> getValues(final ID<K, V> indexId, @NotNull K dataKey, @NotNull final GlobalSearchScope filter) {
    final List<V> values = new SmartList<V>();
    processValuesImpl(indexId, dataKey, true, null, new ValueProcessor<V>() {
      @Override
      public boolean process(final VirtualFile file, final V value) {
        values.add(value);
        return true;
      }
    }, filter);
    return values;
  }

  @NotNull
  public <K, V> Collection<VirtualFile> getContainingFiles(final ID<K, V> indexId, @NotNull K dataKey, @NotNull final GlobalSearchScope filter) {
    final Set<VirtualFile> files = new HashSet<VirtualFile>();
    processValuesImpl(indexId, dataKey, false, null, new ValueProcessor<V>() {
      @Override
      public boolean process(final VirtualFile file, final V value) {
        files.add(file);
        return true;
      }
    }, filter);
    return files;
  }


  public interface ValueProcessor<V> {
    /**
     * @param value a value to process
     * @param file the file the value came from
     * @return false if no further processing is needed, true otherwise
     */
    boolean process(VirtualFile file, V value);
  }

  /**
   * @return false if ValueProcessor.process() returned false; true otherwise or if ValueProcessor was not called at all 
   */
  public <K, V> boolean processValues(final ID<K, V> indexId, @NotNull final K dataKey, @Nullable final VirtualFile inFile,
                                   ValueProcessor<V> processor, @NotNull final GlobalSearchScope filter) {
    return processValuesImpl(indexId, dataKey, false, inFile, processor, filter);
  }


 
  
  private <K, V, R> R processExceptions(final ID<K, V> indexId,
                                        @Nullable final VirtualFile restrictToFile, 
                                        final GlobalSearchScope filter,
                                        ThrowableConvertor<UpdatableIndex<K, V, FileContent>, R, StorageException> computable) {
    try {
      final UpdatableIndex<K, V, FileContent> index = myIndexIndicesManager.getIndex(indexId);
      if (index == null) {
        return null;
      }
      final Project project = filter.getProject();
      //assert project != null : "GlobalSearchScope#getProject() should be not-null for all index queries";
      ensureUpToDate(indexId, project, filter, restrictToFile);

      try {
        index.getReadLock().lock();
        return computable.convert(index);
      }
      finally {
        index.getReadLock().unlock();
      }
    }
    catch (StorageException e) {
      scheduleRebuild(indexId, e);
    }
    catch (RuntimeException e) {
      final Throwable cause = getCauseToRebuildIndex(e);
      if (cause != null) {
        scheduleRebuild(indexId, cause);
      }
      else {
        throw e;
      }
    }
    return null;
  }

  private <K, V> boolean processValuesImpl(final ID<K, V> indexId, final K dataKey, final boolean ensureValueProcessedOnce,
                                           @Nullable final VirtualFile restrictToFile, final ValueProcessor<V> processor,
                                           final GlobalSearchScope filter) {
    ThrowableConvertor<UpdatableIndex<K, V, FileContent>, Boolean, StorageException> keyProcessor = new ThrowableConvertor<UpdatableIndex<K, V, FileContent>, Boolean, StorageException>() {
      @Override
      public Boolean convert(UpdatableIndex<K, V, FileContent> index) throws StorageException {
        final ValueContainer<V> container = index.getData(dataKey);

        boolean shouldContinue = true;

        if (restrictToFile != null) {
          if (restrictToFile instanceof VirtualFileWithId) {
            final int restrictedFileId = getFileId(restrictToFile);
            for (final Iterator<V> valueIt = container.getValueIterator(); valueIt.hasNext(); ) {
              final V value = valueIt.next();
              if (container.isAssociated(value, restrictedFileId)) {
                shouldContinue = processor.process(restrictToFile, value);
                if (!shouldContinue) {
                  break;
                }
              }
            }
          }
        }
        else {
          VALUES_LOOP: for (final Iterator<V> valueIt = container.getValueIterator(); valueIt.hasNext();) {
            final V value = valueIt.next();
            for (final ValueContainer.IntIterator inputIdsIterator = container.getInputIdsIterator(value); inputIdsIterator.hasNext();) {
              final int id = inputIdsIterator.next();
              VirtualFile file = myVfsAdapter.findFileByIdIfCached(id);
              if (file != null && filter.accept(file)) {
                shouldContinue = processor.process(file, value);
                if (!shouldContinue) {
                  break VALUES_LOOP;
                }
                if (ensureValueProcessedOnce) {
                  break; // continue with the next value
                }
              }
            }
          }
        }
        return shouldContinue;
      }
    };
    final Boolean result = processExceptions(indexId, restrictToFile, filter, keyProcessor);
    return result == null || result.booleanValue();
  }
  
  public <K, V> boolean processFilesContainingAllKeys(final ID<K, V> indexId,
                                                      final Collection<K> dataKeys,
                                                      final GlobalSearchScope filter,
                                                      @Nullable Condition<V> valueChecker,
                                                      final Processor<VirtualFile> processor) {
    final TIntHashSet set = collectFileIdsContainingAllKeys(indexId, dataKeys, filter, valueChecker);
    if (set == null) {
      return false;
    }
    return processVirtualFiles(set, filter, processor);
  }

  @Nullable 
  private <K, V> TIntHashSet collectFileIdsContainingAllKeys(final ID<K, V> indexId,
                                                            final Collection<K> dataKeys,
                                                            final GlobalSearchScope filter,
                                                            @Nullable final Condition<V> valueChecker) {
    final ThrowableConvertor<UpdatableIndex<K, V, FileContent>, TIntHashSet, StorageException> convertor =
      new ThrowableConvertor<UpdatableIndex<K, V, FileContent>, TIntHashSet, StorageException>() {
        @Nullable
        @Override
        public TIntHashSet convert(UpdatableIndex<K, V, FileContent> index) throws StorageException {
          TIntHashSet mainIntersection = null;

          for (K dataKey : dataKeys) {
            checkCanceled();
            TIntHashSet copy = new TIntHashSet();
            final ValueContainer<V> container = index.getData(dataKey);

            for (final Iterator<V> valueIt = container.getValueIterator(); valueIt.hasNext(); ) {
              final V value = valueIt.next();
              if (valueChecker != null && !valueChecker.value(value)) {
                continue;
              }
              for (final ValueContainer.IntIterator inputIdsIterator = container.getInputIdsIterator(value); inputIdsIterator.hasNext(); ) {
                final int id = inputIdsIterator.next();
                if (mainIntersection == null || mainIntersection.contains(id)) {
                  copy.add(id);
                }
              }
            }

            mainIntersection = copy;
            if (mainIntersection.isEmpty()) {
              return new TIntHashSet();
            }
          }

          return mainIntersection;
        }
      };


    return processExceptions(indexId, null, filter, convertor);
  }

  private boolean processVirtualFiles(TIntHashSet ids, final GlobalSearchScope filter, final Processor<VirtualFile> processor) {
    return ids.forEach(new TIntProcedure() {
      @Override
      public boolean execute(int id) {
        checkCanceled();
        VirtualFile file = myVfsAdapter.findFileByIdIfCached( id);
        if (file != null && filter.accept(file)) {
          return processor.process(file);
        }
        return true;
      }
    });
  }

  private void checkCanceled() {
    ProgressManager.checkCanceled();
  }

  public static @Nullable Throwable getCauseToRebuildIndex(RuntimeException e) {
    Throwable cause = e.getCause();
    if (cause instanceof StorageException || cause instanceof IOException ||
        cause instanceof IllegalArgumentException || cause instanceof IllegalStateException) return cause;
    return null;
  }

  public <K, V> boolean getFilesWithKey(final ID<K, V> indexId, final Set<K> dataKeys, Processor<VirtualFile> processor, GlobalSearchScope filter) {
    try {
      final UpdatableIndex<K, V, FileContent> index = myIndexIndicesManager.getIndex(indexId);
      if (index == null) {
        return true;
      }
      final Project project = filter.getProject();
      //assert project != null : "GlobalSearchScope#getProject() should be not-null for all index queries";
      ensureUpToDate(indexId, project, filter);

      try {
        index.getReadLock().lock();
        final List<TIntHashSet> locals = new ArrayList<TIntHashSet>();
        for (K dataKey : dataKeys) {
          TIntHashSet local = new TIntHashSet();
          locals.add(local);
          final ValueContainer<V> container = index.getData(dataKey);

          for (final Iterator<V> valueIt = container.getValueIterator(); valueIt.hasNext();) {
            final V value = valueIt.next();
            for (final ValueContainer.IntIterator inputIdsIterator = container.getInputIdsIterator(value); inputIdsIterator.hasNext();) {
              final int id = inputIdsIterator.next();
              local.add(id);
            }
          }
        }

        if (locals.isEmpty()) {
          return true;
        }

        Collections.sort(locals, new Comparator<TIntHashSet>() {
          @Override
          public int compare(TIntHashSet o1, TIntHashSet o2) {
            return o1.size() - o2.size();
          }
        });

        TIntIterator ids = join(locals).iterator();
        while (ids.hasNext()) {
          int id = ids.next();
          //VirtualFile file = IndexInfrastructure.findFileById(fs, id);
          VirtualFile file = myVfsAdapter.findFileByIdIfCached(id);
          if (file != null && filter.accept(file)) {
            if (!processor.process(file)) {
              return false;
            }
          }
        }
      }
      finally {
        index.getReadLock().unlock();
      }
    }
    catch (StorageException e) {
      scheduleRebuild(indexId, e);
    }
    catch (RuntimeException e) {
      final Throwable cause = e.getCause();
      if (cause instanceof StorageException || cause instanceof IOException) {
        scheduleRebuild(indexId, cause);
      }
      else {
        throw e;
      }
    }
    return true;
  }

  private static TIntHashSet join(List<TIntHashSet> locals) {
    TIntHashSet result = locals.get(0);
    if (locals.size() > 1) {
      TIntIterator it = result.iterator();

      while (it.hasNext()) {
        int id = it.next();
        for (int i = 1; i < locals.size(); i++) {
          if (!locals.get(i).contains(id)) {
            it.remove();
            break;
          }
        }
      }
    }
    return result;
  }

  public <K> void scheduleRebuild(final ID<K, ?> indexId, final Throwable e) {
    LOG.info(e);
    requestRebuild(indexId);
    try {
      checkRebuild(indexId, false);
    }
    catch (ProcessCanceledException ignored) {
    }
  }

  private void checkRebuild(final ID<?, ?> indexId, final boolean cleanupOnly) {
    final AtomicInteger status = ourRebuildStatus.get(indexId);
    if (status.get() == OK) return;
    if (status.compareAndSet(REQUIRES_REBUILD, REBUILD_IN_PROGRESS)) {
      cleanupProcessedFlag(null);

      final Runnable rebuildRunnable = new Runnable() {
        @Override
        public void run() {
          try {
            clearIndex(indexId);
            if (!cleanupOnly) {
              scheduleIndexRebuild(false);
            }
          }
          catch (StorageException e) {
            requestRebuild(indexId);
            LOG.info(e);
          }
          finally {
            status.compareAndSet(REBUILD_IN_PROGRESS, OK);
          }
        }
      };

      if (cleanupOnly || myIsUnitTestMode) {
        rebuildRunnable.run();
      }
      else {
        SwingUtilities.invokeLater(new Runnable() {
          @Override
          public void run() {
            new Task.Modal(null, "Updating index", false) {
              @Override
              public void run(@NotNull final ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                rebuildRunnable.run();
              }
            }.queue();
          }
        });
      }
    }

    if (status.get() == REBUILD_IN_PROGRESS) {
      throw new ProcessCanceledException();
    }
  }

  protected abstract void scheduleIndexRebuild(boolean forceDumbMode);

  protected void clearIndex(final ID<?, ?> indexId) throws StorageException {
    final UpdatableIndex<?, ?, FileContent> index = myIndexIndicesManager.getIndex(indexId);
    assert index != null: "Index with key " + indexId + " not found or not registered properly";
    index.clear();
    try {
      IndexInfrastructure.rewriteVersion(IndexInfrastructure.getVersionFile(indexId), myIndexIdToVersionMap.get(indexId));
    }
    catch (IOException e) {
      LOG.error(e);
    }
  }

  protected void cleanupMemoryStorage() {
    myIndexIndicesManager.cleanupMemoryStorage();
  }

  private void dropUnregisteredIndices() {
    final Set<String> indicesToDrop = readRegisteredIndexNames();
    for (ID<?, ?> key : myIndexIndicesManager.keySet()) {
      indicesToDrop.remove(key.toString());
    }
    for (String s : indicesToDrop) {
      FileUtil.delete(IndexInfrastructure.getIndexRootDir(ID.create(s)));
    }
  }

  public void requestRebuild(ID<?, ?> indexId) {
    requestRebuild(indexId, new Throwable());
  }

  public void requestRebuild(ID<?, ?> indexId, Throwable throwable) {
    cleanupProcessedFlag(null);
    LOG.info("Rebuild requested for index " + indexId, throwable);
    ourRebuildStatus.get(indexId).set(REQUIRES_REBUILD);
  }

  public int getNumberOfPendingInvalidations() {
    return myChangedFilesCollector.getNumberOfPendingInvalidations();
  }

  public Collection<VirtualFile> getFilesToUpdate(final Project project) {
    return ContainerUtil.findAll(myChangedFilesCollector.getAllFilesToUpdate(), new Condition<VirtualFile>() {
      @Override
      public boolean value(VirtualFile virtualFile) {
        for (IndexableFileSet set : myIndexableSets) {
          final Project proj = myIndexableSetToProjectMap.get(set);
          if (proj != null && !proj.equals(project)) {
            continue; // skip this set as associated with a different project
          }
          if (set.isInSet(virtualFile)) {
            return true;
          }
        }
        return false;
      }
    });
  }

  public void processRefreshedFile(@NotNull Project project, final com.intellij.ide.caches.FileContent fileContent) {
    myChangedFilesCollector.ensureAllInvalidateTasksCompleted();
    myChangedFilesCollector.processFileImpl(project, fileContent, false);
  }

  public void indexFileContent(@Nullable Project project, com.intellij.ide.caches.FileContent content) {
    myChangedFilesCollector.ensureAllInvalidateTasksCompleted();
    final VirtualFile file = content.getVirtualFile();
    FileContentImpl fc = null;

    PsiFile psiFile = null;

    for (final ID<?, ?> indexId : myIndexIndicesManager.keySet()) {
      if (shouldIndexFile(file, indexId)) {
        if (fc == null) {
          byte[] currentBytes;
          try {
            currentBytes = content.getBytes();
          }
          catch (IOException e) {
            currentBytes = ArrayUtil.EMPTY_BYTE_ARRAY;
          }
          fc = new FileContentImpl(file, currentBytes);

          psiFile = content.getUserData(IndexingDataKeys.PSI_FILE);
          if (psiFile != null) {
            psiFile.putUserData(PsiFileImpl.BUILDING_STUB, true);
            fc.putUserData(IndexingDataKeys.PSI_FILE, psiFile);
          }
          if (project == null) {
            project = ProjectUtil.guessProjectForFile(file);
          }
          fc.putUserData(IndexingDataKeys.PROJECT, project);
        }

        try {
          checkCanceled();
          updateSingleIndex(indexId, file, fc);
        }
        catch (ProcessCanceledException e) {
          myChangedFilesCollector.scheduleForUpdate(file);
          throw e;
        }
        catch (StorageException e) {
          requestRebuild(indexId);
          LOG.info(e);
        }
      }
    }

    if (psiFile != null) {
      psiFile.putUserData(PsiFileImpl.BUILDING_STUB, null);
    }
  }

  private void updateSingleIndex(final ID<?, ?> indexId, final VirtualFile file, final FileContent currentFC)
    throws StorageException {
    if (ourRebuildStatus.get(indexId).get() == REQUIRES_REBUILD) {
      return; // the index is scheduled for rebuild, no need to update
    }
    myLocalModCount++;

    final FileBasedIndexIndicesManager.StorageGuard.Holder lock = myIndexIndicesManager.setDataBufferingEnabled(false);

    try {
      final int inputId = Math.abs(getFileId(file));

      final UpdatableIndex<?, ?, FileContent> index = myIndexIndicesManager.getIndex(indexId);
      assert index != null;

      final Ref<StorageException> exRef = new Ref<StorageException>(null);
      ProgressManager.getInstance().executeNonCancelableSection(new Runnable() {
        @Override
        public void run() {
          try {
            index.update(inputId, currentFC);
          }
          catch (StorageException e) {
            exRef.set(e);
          }
        }
      });
      final StorageException storageException = exRef.get();
      if (storageException != null) {
        throw storageException;
      }
      ApplicationManager.getApplication().runReadAction(new Runnable() {
        @Override
        public void run() {
          if (file.isValid()) {
            if (currentFC != null) {
              IndexingStamp.update(file, indexId, IndexInfrastructure.getIndexCreationStamp(indexId));
            }
            else {
              // mark the file as unindexed
              IndexingStamp.update(file, indexId, -1L);
            }
          }
        }
      });
    }
    finally {
      lock.leave();
    }
  }

  public static int getFileId(final VirtualFile file) {
    if (file instanceof VirtualFileWithId) {
      return ((VirtualFileWithId)file).getId();
    }

    throw new IllegalArgumentException("Virtual file doesn't support id: " + file + ", implementation class: " + file.getClass().getName());
  }

  private boolean needsFileContentLoading(ID<?, ?> indexId) {
    return !myNotRequiringContentIndices.contains(indexId);
  }

  private abstract static class InvalidationTask implements Runnable {
    private final VirtualFile mySubj;

    protected InvalidationTask(final VirtualFile subj) {
      mySubj = subj;
    }

    public VirtualFile getSubj() {
      return mySubj;
    }
  }

  private final class ChangedFilesCollector extends VirtualFileAdapter {
    private final Set<VirtualFile> myFilesToUpdate = new ConcurrentHashSet<VirtualFile>();
    private final Queue<InvalidationTask> myFutureInvalidations = new ConcurrentLinkedQueue<InvalidationTask>();

    // No need to react on movement events since files stay valid, their ids don't change and all associated attributes remain intact.

    @Override
    public void fileCreated(final VirtualFileEvent event) {
      markDirty(event);
    }

    @Override
    public void fileDeleted(final VirtualFileEvent event) {
      myFilesToUpdate.remove(event.getFile()); // no need to update it anymore
    }

    @Override
    public void fileCopied(final VirtualFileCopyEvent event) {
      markDirty(event);
    }

    @Override
    public void beforeFileDeletion(final VirtualFileEvent event) {
      invalidateIndices(event.getFile(), false);
    }

    @Override
    public void beforeContentsChange(final VirtualFileEvent event) {
      invalidateIndices(event.getFile(), true);
    }

    @Override
    public void contentsChanged(final VirtualFileEvent event) {
      markDirty(event);
    }

    @Override
    public void beforePropertyChange(final VirtualFilePropertyEvent event) {
      if (event.getPropertyName().equals(VirtualFile.PROP_NAME)) {
        // indexes may depend on file name
        final VirtualFile file = event.getFile();
        if (!file.isDirectory()) {
          // name change may lead to filetype change so the file might become not indexable
          // in general case have to 'unindex' the file and index it again if needed after the name has been changed
          invalidateIndices(file, false);
        }
      }
    }

    @Override
    public void propertyChanged(final VirtualFilePropertyEvent event) {
      if (event.getPropertyName().equals(VirtualFile.PROP_NAME)) {
        // indexes may depend on file name
        if (!event.getFile().isDirectory()) {
          markDirty(event);
        }
      }
    }

    private void markDirty(final VirtualFileEvent event) {
      final VirtualFile eventFile = event.getFile();
      cleanupProcessedFlag(eventFile);
      iterateIndexableFiles(eventFile, new Processor<VirtualFile>() {
        @Override
        public boolean process(final VirtualFile file) {
          FileContent fileContent = null;
          // handle 'content-less' indices separately
          for (ID<?, ?> indexId : myNotRequiringContentIndices) {
            if (myIndexIndicesManager.getInputFilter(indexId).acceptInput(file)) {
              try {
                if (fileContent == null) {
                  fileContent = new FileContentImpl(file);
                }
                updateSingleIndex(indexId, file, fileContent);
              }
              catch (StorageException e) {
                LOG.info(e);
                requestRebuild(indexId);
              }
            }
          }
          // For 'normal indices' schedule the file for update and stop iteration if at least one index accepts it 
          if (!myLimitsChecker.isTooLarge(file)) {
            for (ID<?, ?> indexId : myIndexIndicesManager.keySet()) {
              if (needsFileContentLoading(indexId) && myIndexIndicesManager.getInputFilter(indexId).acceptInput(file)) {
                scheduleForUpdate(file);
                break; // no need to iterate further, as the file is already marked
              }
            }
          }

          return true;
        }
      });
      IndexingStamp.flushCache();
    }

    public void scheduleForUpdate(VirtualFile file) {
      myFilesToUpdate.add(file);
    }

    void invalidateIndices(final VirtualFile file, final boolean markForReindex) {
      if (isUnderConfigOrSystem(file)) {
        return;
      }
      if (file.isDirectory()) {
        if (isMock(file) ||  myVfsAdapter.wereChildrenAccessed(file)) {
          final Iterable<VirtualFile> children = myVfsAdapter.getChildren(file);
          for (VirtualFile child : children) {
            invalidateIndices(child, markForReindex);
          }
        }
      }
      else {
        cleanupProcessedFlag(file);
        IndexingStamp.flushCache();
        final List<ID<?, ?>> affectedIndices = new ArrayList<ID<?, ?>>(myIndexIndicesManager.size());

        for (final ID<?, ?> indexId : myIndexIndicesManager.keySet()) {
          try {
            if (!needsFileContentLoading(indexId)) {
              if (shouldUpdateIndex(file, indexId)) {
                updateSingleIndex(indexId, file, null);
              }
            }
            else { // the index requires file content
              if (shouldUpdateIndex(file, indexId)) {
                affectedIndices.add(indexId);
              }
            }
          }
          catch (StorageException e) {
            LOG.info(e);
            requestRebuild(indexId);
          }
        }

        if (!affectedIndices.isEmpty()) {
          if (markForReindex && !myLimitsChecker.isTooLarge(file)) {
            // only mark the file as unindexed, reindex will be done lazily
            ApplicationManager.getApplication().runReadAction(new Runnable() {
              @Override
              public void run() {
                for (ID<?, ?> indexId : affectedIndices) {
                  IndexingStamp.update(file, indexId, -2L);
                }
              }
            });
            // the file is for sure not a dir and it was previously indexed by at least one index
            scheduleForUpdate(file);
          }
          else {
            myFutureInvalidations.offer(new InvalidationTask(file) {
              @Override
              public void run() {
                removeFileDataFromIndices(affectedIndices, file);
              }
            });
          }
        }
        if (!markForReindex) {
          final boolean removedFromUpdateQueue = myFilesToUpdate.remove(file);// no need to update it anymore
          if (removedFromUpdateQueue && affectedIndices.isEmpty()) {
            // Currently the file is about to be deleted and previously it was scheduled for update and not processed up to now.
            // Because the file was scheduled for update, at the moment of scheduling it was marked as unindexed, 
            // so, to be on the safe side, we have to schedule data invalidation from all content-requiring indices for this file
            myFutureInvalidations.offer(new InvalidationTask(file) {
              @Override
              public void run() {
                removeFileDataFromIndices(myRequiringContentIndices, file);
              }
            });
          }
        }
        
        IndexingStamp.flushCache();
      }
    }

    private void removeFileDataFromIndices(Collection<ID<?, ?>> affectedIndices, VirtualFile file) {
      Throwable unexpectedError = null;
      for (ID<?, ?> indexId : affectedIndices) {
        try {
          updateSingleIndex(indexId, file, null);
        }
        catch (StorageException e) {
          LOG.info(e);
          requestRebuild(indexId);
        }
        catch (ProcessCanceledException ignored) {
        }
        catch (Throwable e) {
          LOG.info(e);
          if (unexpectedError == null) {
            unexpectedError = e;
          }
        }
      }
      IndexingStamp.flushCache();
      if (unexpectedError != null) {
        LOG.error(unexpectedError);
      }
    }

    public int getNumberOfPendingInvalidations() {
      return myFutureInvalidations.size();
    }

    public void ensureAllInvalidateTasksCompleted() {
      final int size = getNumberOfPendingInvalidations();
      if (size == 0) {
        return;
      }
      final ProgressIndicator indicator = getProgressIndicator();
      indicator.setText("");
      int count = 0;
      while (true) {
        InvalidationTask task = myFutureInvalidations.poll();

        if (task == null) {
          break;
        }
        indicator.setFraction((double)count++ /size);
        indicator.setText2(task.getSubj().getPresentableUrl());
        task.run();
      }
    }

    private void iterateIndexableFiles(final VirtualFile file, final Processor<VirtualFile> processor) {
      if (file.isDirectory()) {
        final ContentIterator iterator = new ContentIterator() {
          @Override
          public boolean processFile(final VirtualFile fileOrDir) {
            if (!fileOrDir.isDirectory()) {
              processor.process(fileOrDir);
            }
            return true;
          }
        };

        for (IndexableFileSet set : myIndexableSets) {
          set.iterateIndexableFilesIn(file, iterator);
        }
      }
      else {
        for (IndexableFileSet set : myIndexableSets) {
          if (set.isInSet(file)) {
            processor.process(file);
            break;
          }
        }
      }
    }

    public Collection<VirtualFile> getAllFilesToUpdate() {
      if (myFilesToUpdate.isEmpty()) {
        return Collections.emptyList();
      }
      return new ArrayList<VirtualFile>(myFilesToUpdate);
    }

    private final Semaphore myForceUpdateSemaphore = new Semaphore();

    private void forceUpdate(@Nullable Project project, @Nullable GlobalSearchScope filter, @Nullable VirtualFile restrictedTo, 
                             boolean onlyRemoveOutdatedData) {
      myChangedFilesCollector.ensureAllInvalidateTasksCompleted();
      for (VirtualFile file: getAllFilesToUpdate()) {
        if (filter == null || filter.accept(file) || file == restrictedTo) {
          try {
            myForceUpdateSemaphore.down();
            // process only files that can affect result
            processFileImpl(project, new com.intellij.ide.caches.FileContent(file), onlyRemoveOutdatedData);
          }
          finally {
            myForceUpdateSemaphore.up();
          }
        }
      }

      // If several threads entered the method at the same time and there were files to update,
      // all the threads should leave the method synchronously after all the files scheduled for update are reindexed,
      // no matter which thread will do reindexing job.
      // Thus we ensure that all the threads that entered the method will get the most recent data

      while (!myForceUpdateSemaphore.waitFor(500)) { // may need to wait until another thread is done with indexing
        if (Thread.holdsLock(PsiLock.LOCK)) {
          break; // hack. Most probably that other indexing threads is waiting for PsiLock, which we're are holding.
        }
      }
    }

    private void processFileImpl(Project project, final com.intellij.ide.caches.FileContent fileContent, boolean onlyRemoveOutdatedData) {
      final VirtualFile file = fileContent.getVirtualFile();
      final boolean reallyRemoved = myFilesToUpdate.remove(file);
      if (reallyRemoved && file.isValid()) {
        if (onlyRemoveOutdatedData) {
          // on shutdown there is no need to re-index the file, just remove outdated data from indices
          final List<ID<?, ?>> affected = new ArrayList<ID<?,?>>();
          for (final ID<?, ?> indexId : myIndexIndicesManager.keySet()) {
            if (myIndexIndicesManager.getInputFilter(indexId).acceptInput(file)) {
              affected.add(indexId);
            }
          }
          removeFileDataFromIndices(affected, file);
        }
        else {
          indexFileContent(project, fileContent);
        }
        IndexingStamp.flushCache();
      }
    }
  }

  private class UnindexedFilesFinder implements CollectingContentIterator {
    private final List<VirtualFile> myFiles = new ArrayList<VirtualFile>();
    private final ProgressIndicator myProgressIndicator;

    private UnindexedFilesFinder() {
      myProgressIndicator = getProgressIndicator();
    }

    @Override
    public List<VirtualFile> getFiles() {
      return myFiles;
    }

    @Override
    public boolean processFile(final VirtualFile file) {
      if (!file.isDirectory()) {
        if (myVfsAdapter.getFlag(file, ALREADY_PROCESSED)) {
          return true;
        }

        if (file instanceof VirtualFileWithId) {
          try {
            FileTypeManagerImpl.cacheFileType(file, file.getFileType());

            boolean oldStuff = true;
            if (!myLimitsChecker.isTooLarge(file)) {
              for (ID<?, ?> indexId : myIndexIndicesManager.keySet()) {
                try {
                  if (needsFileContentLoading(indexId) && shouldIndexFile(file, indexId)) {
                    myFiles.add(file);
                    oldStuff = false;
                    break;
                  }
                }
                catch (RuntimeException e) {
                  final Throwable cause = e.getCause();
                  if (cause instanceof IOException || cause instanceof StorageException) {
                    LOG.info(e);
                    requestRebuild(indexId);
                  }
                  else {
                    throw e;
                  }
                }
              }
            }
            FileContent fileContent = null;
            for (ID<?, ?> indexId : myNotRequiringContentIndices) {
              if (shouldIndexFile(file, indexId)) {
                oldStuff = false;
                try {
                  if (fileContent == null) {
                    fileContent = new FileContentImpl(file);
                  }
                  updateSingleIndex(indexId, file, fileContent);
                }
                catch (StorageException e) {
                  LOG.info(e);
                  requestRebuild(indexId);
                }
              }
            }
            IndexingStamp.flushCache();

            if (oldStuff) {
              myVfsAdapter.setFlag(file, ALREADY_PROCESSED, true);
            }
          }
          finally {
            FileTypeManagerImpl.cacheFileType(file, null);
          }
        }
      }
      else {
        if (myProgressIndicator != null) {
          myProgressIndicator.setText("Scanning files to index");
          myProgressIndicator.setText2(file.getPresentableUrl());
        }
      }
      return true;
    }
  }

  private ProgressIndicator getProgressIndicator() {
    ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
    return indicator != null ? indicator : new EmptyProgressIndicator();
  }

  private boolean shouldUpdateIndex(final VirtualFile file, final ID<?, ?> indexId) {
    return myIndexIndicesManager.getInputFilter(indexId).acceptInput(file) &&
           (isMock(file) || IndexingStamp.isFileIndexed(file, indexId, IndexInfrastructure.getIndexCreationStamp(indexId)));
  }

  private boolean shouldIndexFile(final VirtualFile file, final ID<?, ?> indexId) {
    return myIndexIndicesManager.getInputFilter(indexId).acceptInput(file) &&
           (isMock(file) || !IndexingStamp.isFileIndexed(file, indexId, IndexInfrastructure.getIndexCreationStamp(indexId)));
  }

  private boolean isUnderConfigOrSystem(VirtualFile file) {
    final String filePath = file.getPath();
    return myConfigPath != null && FileUtil.startsWith(filePath, myConfigPath) ||
           mySystemPath != null && FileUtil.startsWith(filePath, mySystemPath);
  }

  private boolean isMock(final VirtualFile file) {
    return myVfsAdapter.isMock(file);
  }

  public CollectingContentIterator createContentIterator() {
    return new UnindexedFilesFinder();
  }

  public void registerIndexableSet(IndexableFileSet set, @Nullable Project project) {
    myIndexableSets.add(set);
    myIndexableSetToProjectMap.put(set, project);
  }

  public void removeIndexableSet(IndexableFileSet set) {
    myChangedFilesCollector.forceUpdate(null, null, null, true);
    myIndexableSets.remove(set);
    myIndexableSetToProjectMap.remove(set);
  }

  private static class IndexableFilesFilter implements FileBasedIndexIndicesManager.InputFilter {
    private final FileBasedIndexIndicesManager.InputFilter myDelegate;

    private IndexableFilesFilter(FileBasedIndexIndicesManager.InputFilter delegate) {
      myDelegate = delegate;
    }

    @Override
    public boolean acceptInput(final VirtualFile file) {
      return file instanceof VirtualFileWithId && myDelegate.acceptInput(file);
    }
  }

  protected void cleanupProcessedFlag(VirtualFile root) {
    myVfsAdapter.iterateCachedFilesRecursively(root, new VirtualFileVisitor() {
      @Override
      public boolean visitFile(VirtualFile file) {
        if (!file.isDirectory()) {
          myVfsAdapter.setFlag(file, ALREADY_PROCESSED, false);
        }
        return true;
      }
    });
  }
}
