package com.github.sardine.ant.command;

import com.github.sardine.DavResource;
import com.github.sardine.ant.Command;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.apache.tools.ant.Project;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.MessageFormat;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A nice ant wrapper around sardine.list() and sardine.get().
 *
 * @author andreafonti
 */
public class Get extends Command {
    private final static String DBF = "sardineDB";
    public static final String SARDINE = ".sardine";
    /**
     * Webdav server url
     */
    String serverUrl;
    /**
     * Remote directory path
     */
    String remoteDirectory;
    /**
     * Local directory path
     */
    String localDirectory;

    /**
     * true if existent local files will be overwritten; otherwise, false.
     */
    boolean overwriteFiles = false;

    /**
     * true if existent local files will be skipped; otherwise, false.
     */
    boolean skipExistingFiles = false;

    /**
     * 断点续传开启,默认不开启
     */
    boolean resumeBroken = false;
    /**
     * 断点续传会生成数据库,是否自动先清理
     */
    boolean clearCache = true;
    /**
     * 下载线程量
     */
    int threadCount = 8;

    private ThreadPoolExecutor threadPoolExecutor;

    private AtomicInteger queueSize = new AtomicInteger(0);
    /**
     * {@inheritDoc}
     */
    @Override
    protected void validateAttributes() throws Exception {
        StringBuilder sb = new StringBuilder();

        if (serverUrl == null) {
            sb.append("[serverUrl] must not be null\n");
        }
        if (remoteDirectory == null) {
            sb.append("[remoteDirectory] must not be null\n");
        }
        if (localDirectory == null) {
            sb.append("[localDirectory] must not be null\n");
        }

        if (sb.length() > 0) {
            throw new IllegalArgumentException(sb.substring(0, sb.length() - 1));
        }

        File cacheDir = new File(System.getProperty("user.home") + File.separator + SARDINE);
        if (!cacheDir.exists()) {
            FileUtils.forceMkdir(cacheDir);
        }

        if (clearCache) {
            FileUtils.cleanDirectory(cacheDir);
        }

    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void execute() throws Exception {

        // add an extra leading slash, if it will be swallowed by resolve if
        // duplicated
        Queue<DevResourceEntry> urls = new LinkedList<>();

        DB db = null;
        if (resumeBroken) {
            File dbf = new File(System.getProperty("user.home") + File.separator + SARDINE + File.separator + DBF);
            DBMaker maker = DBMaker.newFileDB(dbf);
            db = maker.make();
            String queueName = generateUniqueQueueName();
            try {
                urls = db.getQueue(queueName);
            } catch (Exception e) {
                urls = db.createQueue(queueName, new DevResourceEntrySerializer(), true);
            }
        }

        threadPoolExecutor = new ThreadPoolExecutor(threadCount, threadCount, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue(), new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable);
                thread.setName("Sardine-downloader-" + System.currentTimeMillis());
                thread.setDaemon(true);
                return thread;
            }
        });
        queueSize.set(0);
        if((!resumeBroken)||urls.isEmpty()){
            URI remoteDirectoryUrl = new URI(serverUrl + '/').resolve(remoteDirectory);
            List<DavResource> resource = getSardine().list(remoteDirectoryUrl.toString(), 1);
            //initial push back to queue
            for (DavResource davResource : resource) {
                DevResourceEntry entry = toEntry(davResource);
                log(entry.toString(), Project.MSG_DEBUG);
                urls.offer(entry);
                if(!davResource.isDirectory()){
                    queueSize.incrementAndGet();
                }
            }
        }
        long lastReportMillisenconds=System.currentTimeMillis();
        while (!urls.isEmpty()) {
            long current = System.currentTimeMillis();
            //report process each 4 sec
            if(current-lastReportMillisenconds>4000){
                log(MessageFormat.format("\rstatus: queued download {0}. completed download {1}", queueSize.get(), threadPoolExecutor.getCompletedTaskCount()));
                lastReportMillisenconds = current;
            }
            final DevResourceEntry entry = urls.peek();
            if (entry.isDirectory) {
                //list it
                log("will going to list " + entry.uri, Project.MSG_DEBUG);
                Path localFilePath = Paths.get(localDirectory, entry.path);
                try {
                    if (!Files.exists(localFilePath)) {
                        Files.createDirectories(localFilePath);
                    }
                    List<DavResource> subResource = getSardine().list(entry.uri, 1);
                    for (DavResource davResource : subResource) {
                        DevResourceEntry subEntry = toEntry(davResource);
                        log(subEntry.toString(), Project.MSG_DEBUG);
                        urls.offer(subEntry);
                        if(!davResource.isDirectory()){
                            queueSize.incrementAndGet();
                        }
                    }
                } catch (IOException e) {
                    log(e.getMessage(), Project.MSG_ERR);
                    e.printStackTrace();
                }
            } else {
				download(urls, entry);
            }
            urls.poll();
        }
        log("downloaded files to " + localDirectory, Project.MSG_DEBUG);
        threadPoolExecutor.shutdown();
        if(db!=null){
            db.commit();
            db.close();
        }
    }

    private void download(final Queue<DevResourceEntry> urls, final DevResourceEntry fileEntry) throws IOException {
        Path localFilePath = Paths.get(localDirectory, fileEntry.path);

        //already exits
        if (skipExistingFiles && Files.exists(localFilePath) ) {
            if(Files.size(localFilePath)==fileEntry.length){
                log("skipping download of already existing file " + localFilePath, Project.MSG_DEBUG);
                return;
            }
            log("file length not same, broken file, will download "+fileEntry.path+" again.", Project.MSG_DEBUG);
            Files.delete(localFilePath);
        }

        //check parent
        Path parentPath = localFilePath.getParent();
        if (!Files.exists(parentPath)) {
            try {
                Files.createDirectories(parentPath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

		//put to thread for downloading
		threadPoolExecutor.execute(() -> {
			log("downloading " + fileEntry.path + " to " + localFilePath, Project.MSG_DEBUG);

			InputStream ioStream = null;
			try {
				ioStream = getSardine().get(fileEntry.uri);
				if (overwriteFiles) {
					Files.copy(ioStream, localFilePath, StandardCopyOption.REPLACE_EXISTING);
				} else {
					Files.copy(ioStream, localFilePath);
				}
                queueSize.decrementAndGet();
			} catch (Exception e) {
				log("download error: "+e.getMessage(), Project.MSG_ERR);
				e.printStackTrace();
				log("download "+fileEntry.path+" failed offer to queue again");
				urls.offer(fileEntry);
			} finally {
				if(ioStream!=null){
					try {
						ioStream.close();
					} catch (IOException e) {
						log("close io exception"+e.getMessage(), Project.MSG_DEBUG);
						e.printStackTrace();
					}
				}
			}
		});
    }

    private DevResourceEntry toEntry(DavResource davResource) {
        DevResourceEntry entry = new DevResourceEntry();
        entry.uri = serverUrl + davResource.getHref().toString();
        entry.isDirectory = davResource.isDirectory();
        entry.length = davResource.getContentLength();
        entry.path = davResource.getPath();
        return entry;
    }

    private String generateUniqueQueueName() {
        StringBuilder sb = new StringBuilder();
        sb.append(serverUrl).append(localDirectory).append(remoteDirectory).append(overwriteFiles).append(skipExistingFiles);
        String dbf = Base64.encodeBase64String(sb.toString().getBytes(StandardCharsets.UTF_8));
        return dbf;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public void setRemoteDirectory(String remoteDirectory) {
        this.remoteDirectory = remoteDirectory;
    }

    public void setLocalDirectory(String localDirectory) {
        this.localDirectory = localDirectory;
    }

    public void setOverwriteFiles(boolean overwriteFiles) {
        this.overwriteFiles = overwriteFiles;
    }

    public void setSkipExistingFiles(boolean skipExistingFiles) {
        this.skipExistingFiles = skipExistingFiles;
    }

    public void setResumeBroken(boolean resumeBroken) {
        this.resumeBroken = resumeBroken;
    }

    public void setClearCache(boolean clearCache) {
        this.clearCache = clearCache;
    }

    public void setThreadCount(int threadCount) {
        this.threadCount = threadCount;
    }

    static class DevResourceEntry implements Serializable, Comparable {
        String uri;
        boolean isDirectory;
        long length;
        String path;

        @Override
        public String toString() {
            return path+"/"+length;
        }

        @Override
        public int compareTo(Object o) {
            if (!(o instanceof DevResourceEntry)) {
                return 1;
            }
            return this.uri.compareTo(((DevResourceEntry) o).uri);
        }
    }

    static class DevResourceEntrySerializer implements Serializer<DevResourceEntry>, Serializable {

        @Override
        public void serialize(DataOutput out, DevResourceEntry value) throws IOException {
            out.writeUTF(value.uri);
            out.writeBoolean(value.isDirectory);
            out.writeLong(value.length);
            out.writeUTF(value.path);
        }

        @Override
        public DevResourceEntry deserialize(DataInput in, int available) throws IOException {
            DevResourceEntry entry = new DevResourceEntry();
            entry.uri = in.readUTF();
            entry.isDirectory = in.readBoolean();
            entry.length = in.readLong();
            entry.path = in.readUTF();
            return entry;
        }

        @Override
        public int fixedSize() {
            return -1;
        }
    }
}
