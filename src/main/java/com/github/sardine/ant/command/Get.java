package com.github.sardine.ant.command;

import com.github.sardine.DavResource;
import com.github.sardine.ant.Command;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A nice ant wrapper around sardine.list() and sardine.get().
 *
 * @author andreafonti
 */
public class Get extends Command {
	private final static String DBF="sardineDB";
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
	int threadCount=8;

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

		File cacheDir=new File(System.getProperty("user.home")+File.separator+ SARDINE);
		if(!cacheDir.exists()){
			FileUtils.forceMkdir(cacheDir);
		}

		if(clearCache){
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
		Queue<String> urls=new LinkedBlockingQueue<>();
		DB db=null;
		if(resumeBroken){
			File dbf=new File(System.getProperty("user.home")+File.separator+ SARDINE+File.separator+DBF);
			DBMaker maker=DBMaker.newFileDB(dbf);
			db=maker.make();
			String queueName = generateUniqueQueueName();
			try {
				urls=db.getQueue(queueName);
			}catch (Exception e){
				urls=db.createQueue(queueName, Serializer.STRING, true);
			}
		}

		URI remoteDirectoryUrl = new URI(serverUrl + '/').resolve(remoteDirectory);

		String remoteDirectoryPath = remoteDirectoryUrl.getPath();

		List<DavResource> resource = getSardine().list(remoteDirectoryUrl.toString(), -1);

		for (DavResource davResource : resource) {
			if (!davResource.isDirectory()) {
				String filePathRelativeToRemoteDirectory = davResource.getPath().replace(remoteDirectoryPath, "");
				Path localFilePath = Paths.get(localDirectory, filePathRelativeToRemoteDirectory);

				if (skipExistingFiles && Files.exists(localFilePath)) {
					log("skipping download of already existing file " + localFilePath);
					continue;
				}

				Files.createDirectories(localFilePath.getParent());

				log("downloading " + filePathRelativeToRemoteDirectory + " to " + localFilePath);

				InputStream ioStream = getSardine().get(serverUrl + davResource.getHref().toString());
				try {
					if (overwriteFiles) {
						Files.copy(ioStream, localFilePath, StandardCopyOption.REPLACE_EXISTING);
					} else {
						Files.copy(ioStream, localFilePath);
					}

				} finally {
					ioStream.close();
				}
			}
		}
		log("downloaded files to " + localDirectory);
	}

	private String generateUniqueQueueName(){
		StringBuilder sb = new StringBuilder();
		sb.append(serverUrl).append(localDirectory).append(remoteDirectory).append(overwriteFiles).append(skipExistingFiles);
		String dbf=Base64.encodeBase64String(sb.toString().getBytes(StandardCharsets.UTF_8));
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
}
