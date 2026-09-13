package com.mirwanda.nottiled;

import java.io.InputStream;
import java.util.List;

// 注：Swing 文件选择框仅存在于下方注释块中，Android 平台不可用，故移除 import
public class nullInterface implements Interface {
    @Override
    public void showbanner(boolean show) {

    }

    @Override
    public void showinterstitial() {

    }

    @Override
    public boolean buyadfree() {
        return false;
    }

    @Override
    public boolean ispro() {
        return false;
    }

    @Override
    public String getVersione() {
        return "1.8.6-beta1";
    }

    @Override
    public String getFilename() {
        return null;
    }

    @Override
    public String getUri() {
        return null;
    }

    @Override
    public String getStatus() {
        return null;
    }

    @Override
    public String getOS() {
        return "desktop";
    }

    @Override
    public byte[] getData() {
        return null;
    }

    @Override
    public List<byte[]> getDatas() {
        return null;
    }

    @Override
    public List<String> getFilenames() {
        return null;
    }

    /*
    @Override
    public String openDialog() {
        pet="";
        new Thread( new Runnable() {
            @Override
            public void run() {
                JFileChooser chooser = new JFileChooser();
                //chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                //chooser.showSaveDialog(null);

                JFrame f = new JFrame();
                f.setVisible( true );
                f.toFront();
                f.setAlwaysOnTop( true );
                f.setVisible( false );

                int res = chooser.showOpenDialog( f );
                f.dispose();
                if (res == JFileChooser.APPROVE_OPTION) {
                    pet = chooser.getSelectedFile().getAbsolutePath();
                }else{
                    pet = "cancel";
                }

            }
        } ).start();
        return null;
    }

    @Override
    public String openDirectory() {
        pet="";
        new Thread( new Runnable() {
            @Override
            public void run() {
                JFileChooser chooser = new JFileChooser();
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                //chooser.showSaveDialog(null);

                JFrame f = new JFrame();
                f.setVisible( true );
                f.toFront();
                f.setAlwaysOnTop( true );
                f.setVisible( false );

                int res = chooser.showSaveDialog( f );
                f.dispose();
                if (res == JFileChooser.APPROVE_OPTION) {
                    pet = chooser.getSelectedFile().getAbsolutePath();
                }else{
                    pet = "cancel";
                }

            }
        } ).start();
        return null;
    }
*/


    @Override
    public void speak(String s) {

    }

    @Override
    public void changelanguage(String lang) {

    }

    @Override
    public void saveFile(String data) {

    }

    @Override
    public void saveasFile(String data, String aaa) {

    }

    @Override
    public void saveasFile(byte[] data, String aaa) {

    }

    @Override
    public void openFile() {

    }

    @Override
    public void newFile() {

    }

    @Override
    public void selectFolder() {
    
    }
    
    @Override
    public void pickFolderOnly() {
    
    }
    
    @Override
    public void readTree(String uriString) {
    
    }
    
    @Override
    public boolean saveToUri(String uriString, byte[] data) {
        return false;
    }
    
    @Override
    public boolean saveToFolderTree(String treeUri, String filename, byte[] data) {
        return false;
    }
    
    @Override
    public void clearSAFstatus() {
    
    }
    
    @Override
    public String getdatafromURI(String URI) {
        return null;
    }

    @Override
    public void setOrientation(int ori) {

    }

    // ===== 自动更新：桌面空实现 =====
    @Override
    public int getVersionCode() {
        return 0;
    }

    @Override
    public void downloadApk(String url, String saveName, ApkDownloader cb) {
        if (cb != null) cb.onError("桌面端不支持自动更新");
    }

    @Override
    public void installApk(String absolutePath) {
        // 桌面端无需安装
    }

    @Override
    public String getUpdateFilePath(String name) {
        return null;
    }

    @Override
    public String findLocalUpdateApk(String name, long size) {
        return null;
    }

    @Override
    public void openFolder(String dirPath) {
        // 桌面端无系统文件管理器跳转
    }

}
