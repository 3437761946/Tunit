package com.mirwanda.nottiled;

import java.io.InputStream;

public interface Interface
{
	public void showbanner(boolean show);
	public void showinterstitial();
	public boolean buyadfree();
	public boolean ispro();
	public String getVersione();
	public byte[] getData();
	public java.util.List<byte[]> getDatas();
	public java.util.List<String> getFilenames();
	public String getFilename();
	public String getUri();
	public String getStatus();
	public String getOS();
	public void speak(final String s);
	public void changelanguage(final String lang);
	public void saveFile(String data);
	public void saveasFile(String data, String suggestedfilename);
	public void saveasFile(byte[] data, String suggestedfilename);
	public void openFile();
	public void newFile();
	public void selectFolder();
	public void pickFolderOnly();
	public void readTree(String uriString);
	public boolean saveToUri(String uriString, byte[] data);
	public boolean saveToFolderTree(String treeUri, String filename, byte[] data);
	public void clearSAFstatus();
	public String getdatafromURI(String URI);
	public void setOrientation(int ori);

	// ===== 自动更新（平台相关）=====
	/** 当前 App 的 versionCode（用于与服务器最新版本比较）。 */
	public int getVersionCode();
	/** 后台下载 APK 到私有目录；进度/结果通过回调返回（回调需在 UI 线程执行）。 */
	public void downloadApk(final String url, final String saveName, final ApkDownloader cb);
	/** 拉起系统安装器安装私有目录中已下载的 APK（absolutePath 为绝对路径）。 */
	public void installApk(final String absolutePath);
	/** 返回用于保存更新 APK 的本地绝对路径（位于应用私有 update 目录，供系统安装器共享）；桌面端可返回 null。 */
	public String getUpdateFilePath(final String name);
	/** 在本地（私有 update 目录 / 公共“下载/Tunit”）查找已下载的安装包；命中返回可交给 {@link #installApk(String)} 的绝对路径，未命中返回 null。
	 *  name 为空时匹配任意 .apk；size>0 时要求文件大小一致（用于过滤残缺文件）。 */
	public String findLocalUpdateApk(final String name, final long size);
	/** 在系统文件管理器中定位并打开指定目录（绝对路径），供无法自动安装时手动安装；桌面端可空实现。 */
	public void openFolder(final String dirPath);

	/** 下载回调：实现方需保证回调在 UI 线程执行。 */
	public interface ApkDownloader {
		void onProgress(int percent);
		void onSuccess(String savedPath);
		void onError(String message);
	}

}
