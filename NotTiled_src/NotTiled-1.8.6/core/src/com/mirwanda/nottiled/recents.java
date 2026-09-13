package com.mirwanda.nottiled;

import com.badlogic.gdx.Gdx;

import java.util.*;

public class recents
{
	public recents(){}
	
	private java.util.List<String> paths = new ArrayList<String>();
	private java.util.List<String> filenames = new ArrayList<String>();
	private java.util.List<String> types = new ArrayList<String>();

	public void setPaths(java.util.List<String> paths)
	{
		this.paths = paths;
	}

	public java.util.List<String> getPaths()
	{
		return paths;
	}
	public java.util.List<String> getFilenames()
	{
		return filenames;
	}
	public java.util.List<String> getTypes()
	{
		return types;
	}

	public void addrecent(String path, String filename, String type){
		try {

			for (int i = paths.size() - 1; i >= 0; i--) {
				if (paths.get( i ) != null) {
					if (paths.get( i ).equalsIgnoreCase( path )) {
						paths.remove( i );
						filenames.remove( i );
						types.remove( i );
					}
				}
			}

			paths.add(0, path);
			filenames.add(0, filename );
			types.add( 0, type );

			if (paths.size() > 20) {
				paths.remove( paths.size()-1 );
				filenames.remove( filenames.size()-1 );
				types.remove( types.size()-1 );
			}
		}catch(Exception e){
			paths.clear();
			filenames.clear();
			types.clear();

		}
	}

	// 删除指定下标的最近记录（路径/文件名/类型同步移除），供「最近打开的文件」界面删除使用
	public void removeRecent(int index){
		try {
			if (index < 0 || index >= paths.size()) return;
			paths.remove( index );
			if (index < filenames.size()) filenames.remove( index );
			if (index < types.size()) types.remove( index );
		}catch(Exception e){
			e.printStackTrace();
		}
	}
}
