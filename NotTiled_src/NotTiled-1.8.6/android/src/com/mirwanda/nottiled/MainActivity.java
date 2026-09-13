package com.mirwanda.nottiled;

import android.*;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.speech.tts.*;

import com.badlogic.gdx.backends.android.*;
//import com.google.android.gms.ads.*;//

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
//import javax.annotation.*;
//import org.solovyev.android.checkout.*;

//import com.mirwanda.nottiled.BuildConfig;//


public class MainActivity extends AndroidApplication implements Interface
{

	private static final int CREATE_REQUEST_CODE = 40;
	private static final int OPEN_REQUEST_CODE = 41;
	private static final int REGAIN_ACCESS_CODE = 44;
	private static final int SAVE_REQUEST_CODE = 42;
	private static final int SAVEAS_REQUEST_CODE = 43;
	private static final int BINARY_CREATE_CODE = 39;
	private static final int REQUEST_TREE_CODE = 45;
	private static final int REQUEST_TREE_ONLY_CODE = 46;
	Uri currentMAP = null;
	SharedPreferences myPrefs;
	SharedPreferences.Editor prefs;
	//TextToSpeech tts;
	PackageInfo pInfo;
	String version;
	@Override
	public void speak(final String s)
	{
		
			
		//tts.speak(s, TextToSpeech.QUEUE_FLUSH, null);
		
		// TODO: Implement this method
	}

	@Override
	public AndroidAudio createAudio(Context context, AndroidApplicationConfiguration config){
	return new AsynchronousAndroidAudio( context, config );
	}

	@Override
	public void changelanguage(String lang)
	{

	switch (lang)
	{
		/*
		case "English":
			tts.setLanguage(Locale.ENGLISH);
			break;
		case "Bahasa Indonesia":
			tts.setLanguage(new Locale("in_ID"));
			break;
		case "Spanish":
			tts.setLanguage(new Locale("es_ES"));
			break;
		case "French":
			tts.setLanguage(new Locale("fr_FR"));
			break;
		case "Chinese":
			tts.setLanguage(new Locale("cmn_CN"));
			break;
		case "Japanese":
			tts.setLanguage(new Locale("ja_JP"));
			break;
		case "Russian":
			tts.setLanguage(new Locale("ru_RU"));
			break;
		case "Portuguese":
			tts.setLanguage(new Locale("pt_PT"));
			break;
		case "Tagalog":
			tts.setLanguage(new Locale("fil_PH"));
			break;

		 */
	}
		
		// TODO: Implement this method
	}
	
	
	@Override
	public boolean ispro()
	{
		return proVersion;
	}

	@Override
	public String getVersione()
	{
		return version;
	}





	@Override
	public byte[] getData() {
		return SAFdata;
	}

	@Override
	public java.util.List<byte[]> getDatas() {
		return SAFdatas;
	}

	@Override
	public String getFilename() {
		return SAFfilename;
	}

	@Override
	public String getUri() {
		return SAFuri;
	}

	@Override
	public java.util.List<String> getFilenames() {
		return SAFfilenames;
	}

	@Override
	public String getStatus() {
		return SAFstatus;
	}

	@Override
	public String getOS() {
		return vers;
	}


	//private ActivityCheckout mCheckout;
	private static final String AD_FREE = "adfree";
	boolean proVersion = true;
	/**/
	//public AdView adView;//
	//private InterstitialAd mInterstitialAd;//
	/**/
	
	@Override
	public void showinterstitial(){
		runOnUiThread(new Runnable() {
				@Override
				public void run() {
					if (proVersion) return;
					//mInterstitialAd.show();//
				}
			});
	}
	
	@Override
	public void showbanner(final boolean show)
	{
		runOnUiThread(new Runnable() {
				@Override
				public void run() {
					/**/
					if (proVersion) 
					{
						//adView.setVisibility(View.GONE);
						return;
					}
					if (show){
						//adView.setVisibility(View.VISIBLE);
					}else
					{
						//adView.setVisibility(View.GONE);
					}
					/**/
				}
			});
	}

	@Override
	public boolean buyadfree()
	{
		runOnUiThread(new Runnable() {
				@Override
				public void run() {
					//mCheckout.startPurchaseFlow(ProductTypes.IN_APP, AD_FREE, null, new PurchaseListener());
				}
			});
		return true;
	}
	
	
	
	
	
	
	
/*
    private class PurchaseListener extends EmptyRequestListener<Purchase> {
        @Override
        public void onSuccess(@Nonnull Purchase purchase) {
          	proVersion=true;
        }
    }

    private class InventoryCallback implements Inventory.Callback {
        @Override
        public void onLoaded(@Nonnull Inventory.Products products) {
            final Inventory.Product product = products.get(ProductTypes.IN_APP);
            if (!product.supported) {
                return;
            }
            if (product.isPurchased(AD_FREE)) {
				proVersion= true;
                return;
            }
        }
    }

 */

	final static int APP_STORAGE_ACCESS_REQUEST_CODE = 501; // Any value
	public String vers;
	public void requestAccess(){
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			vers="android10+";
		}else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.R ) {
			vers="android9-";
			if (this.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
				this.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1024);
			}
		}

	}

	public void runGDX(){
		String intend="";
		Intent intent = getIntent();
		if (intent.getData()!=null) intend=intent.getData().toString();

		AndroidApplicationConfiguration cfg = new AndroidApplicationConfiguration();
		initialize(new MyGdxGame(intend,this), cfg);

	}

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        myPrefs = getSharedPreferences("NotTiled", 0);
		prefs = myPrefs.edit();

		try {
			currentMAP = Uri.parse(myPrefs.getString("url", ""));
		} catch (IllegalArgumentException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}


		requestAccess();
		// 启动时静默检测是否有“已下载未安装”的更新包（不再主动跳转权限设置页，避免每次进入软件被打扰）
		checkPendingUpdatePermission();
		runGDX();


		try {
			pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
			version = pInfo.versionName;
		} catch (PackageManager.NameNotFoundException e) {
			e.printStackTrace();
		}
		/*
		tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {

				@Override
				public void onInit(int status) {
					if(status != TextToSpeech.ERROR) {
						tts.setLanguage(Locale.UK);
						tts.setPitch(1f);
						tts.setSpeechRate(1f);
						//   tts.speak(SC_str, TextToSpeech.QUEUE_FLUSH, null,null);
					}
				}
			});

		 */


//		final Billing billing = Aplikasi.get().getBilling();
 //       mCheckout = Checkout.forActivity(this, billing);
  //      mCheckout.start();
   //     mCheckout.loadInventory(Inventory.Request.create().loadAllPurchases(), new InventoryCallback());

		/**/

		//RelativeLayout layout = new RelativeLayout(this);

        // Do the stuff that initialize() would do for you
		/*
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
							 WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
     */


        // Create the libgdx View
        //View gameView = initializeForView(new MyGdxGame(intend,this));
		//layout.addView(gameView);


		//Create and setup interstitial

		/*
		mInterstitialAd = new InterstitialAd(this);
		String ads;
		if (BuildConfig.DEBUG) {
			ads = "ca-app-pub-3940256099942544/1033173712";
		}else{
			ads = "ca-app-pub-0329741361926795/8939201077";
		}

		if (!proVersion){
			mInterstitialAd.setAdUnitId(ads);
			mInterstitialAd.loadAd(new AdRequest.Builder().build());
			mInterstitialAd.setAdListener(new AdListener() {
					@Override
					public void onAdClosed() {
						mInterstitialAd.loadAd(new AdRequest.Builder().build());
					}
				});
		}

        // Create and setup the Banner
        adView = new AdView(this);
        adView.setAdSize(AdSize.BANNER);
		if (BuildConfig.DEBUG){
			adView.setAdUnitId("ca-app-pub-3940256099942544/6300978111"); // Put in your secret key here
		}else
		{
        	adView.setAdUnitId("ca-app-pub-0329741361926795/9130772767"); // Put in your secret key here
		}

        AdRequest adRequest = new AdRequest.Builder().build();
        adView.loadAd(adRequest);
		adView.setVisibility(View.GONE);
        // Add the libgdx view

        // Add the AdMob view
        RelativeLayout.LayoutParams adParams =
        	new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT,
											RelativeLayout.LayoutParams.WRAP_CONTENT);
        adParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        adParams.addRule(RelativeLayout.CENTER_HORIZONTAL);

        layout.addView(adView, adParams);

		 */

        // Hook it all up
        //setContentView(layout);
		/**/

    }

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged( hasFocus );



		/* black bar.
		if (hasFocus){
			getWindow().getDecorView().setSystemUiVisibility(
					View.SYSTEM_UI_FLAG_LAYOUT_STABLE
							| View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
							| View.SYSTEM_UI_FLAG_FULLSCREEN
							| View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
							| View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY );
		}

		 */
	}

	// 系统文件/文件夹选择器「在途」互斥：仅当上一个选择器尚未返回时才拒绝再次拉起。
	// 注意：绝不能用「时间窗节流」（旧实现用 2500ms）来防重复——用户在取消/快速返回后立刻重试时，
	// 新的一次拉起会被静默丢掉、不给上层任何状态，导致上层的等待线程一直等满超时、并一直占着
	// nativePickerRunning 互斥标志，表现为「在“地形设置”里多次点击按钮，系统选择器始终不弹出、无反应」。
	private volatile boolean pickerInFlight = false;

	private void launchIntent(final Intent intent, final int code) {
		if (pickerInFlight) {
			android.util.Log.i( "SAF-GUARD", "skip SAF launch, previous picker still in flight, code=" + code );
			return;
		}
		pickerInFlight = true;
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				try {
					startActivityForResult(intent, code);
				} catch (Exception e) {
					e.printStackTrace();
					pickerInFlight = false;
					SAFstatus = "cancel";
				}
			}
		});
	}

	@Override
	public void newFile()
	{
		SAFstatus="";
		SAFdata=null;
		Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);

		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.setType("*/*");
		intent.addFlags(
				Intent.FLAG_GRANT_READ_URI_PERMISSION
						| Intent.FLAG_GRANT_WRITE_URI_PERMISSION
						| Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
						| Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
		intent.putExtra(Intent.EXTRA_TITLE, "newfile.tmx");
		launchIntent(intent, CREATE_REQUEST_CODE);
	}

	@Override
	public void openFile()
	{
		SAFstatus="";
		SAFdata=null;

		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.addFlags(
				Intent.FLAG_GRANT_READ_URI_PERMISSION
						| Intent.FLAG_GRANT_WRITE_URI_PERMISSION
						| Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
						| Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
		intent.setType("*/*");
		launchIntent(intent, OPEN_REQUEST_CODE);
	}

	@Override
	public void selectFolder()
	{
		SAFstatus="";
		SAFdata=null;
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
		intent.addFlags(
				Intent.FLAG_GRANT_READ_URI_PERMISSION
						| Intent.FLAG_GRANT_WRITE_URI_PERMISSION
						| Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
						| Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
		launchIntent(intent, REQUEST_TREE_CODE);
	}

	@Override
	public void pickFolderOnly()
	{
		SAFstatus="";
		SAFdata=null;
		SAFuri="";
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
		intent.addFlags(
				Intent.FLAG_GRANT_READ_URI_PERMISSION
						| Intent.FLAG_GRANT_WRITE_URI_PERMISSION
						| Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
						| Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
		launchIntent(intent, REQUEST_TREE_ONLY_CODE);
	}

	// 二次打开：凭已持久化的目录授权(tree Uri)直接重新读取目录内容，无需再弹系统选择器
	@Override
	public void readTree(final String uriString)
	{
		SAFstatus="";
		SAFuri="";
		SAFfilename="";
		SAFdata=null;
		SAFdatas=null;
		SAFfilenames=null;
		if (uriString == null || uriString.isEmpty()) {
			SAFstatus = "error";
			return;
		}
		final Uri tree = Uri.parse( uriString );
		new Thread( new Runnable() {
			@Override
			public void run() {
				try {
					java.util.List<Uri> docs = readFiles( tree );
					java.util.List<byte[]> datas = new ArrayList<byte[]>();
					java.util.List<String> names = new ArrayList<String>();
					for (Uri u : docs) {
						try {
							byte[] b = readBytes( u );
							String s = getFileName( u );
							if (b != null && s != null) {
								datas.add( b );
								names.add( s );
							}
						} catch (Exception e) {
							// 单个文件读取失败不中断，继续尝试下一个
						}
					}
					SAFdatas = datas;
					SAFfilenames = names;
					SAFuri = tree.toString();
					SAFfilename = folderDisplayName( tree );
					SAFstatus = "ok";
				} catch (Exception e) {
					e.printStackTrace();
					SAFstatus = "error";
				}
			}
		} ).start();
	}

	@Override
	public String getdatafromURI(String URI)
	{
		currentMAP = Uri.parse(URI);
		//
		prefs.putString("url", currentMAP.toString());
		prefs.commit();
		try {
			String s = readFileContent( currentMAP );
			return s;
		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
	}

	@Override
	public void setOrientation(int ori) {
		switch (ori){
			case 0: //both
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
				break;
			case 1: //landscape
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
				break;
			case 2: //portrait
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
				break;
		}
	}

	@Override
	public void saveFile(String data)
	{
		if (currentMAP ==null) {
			SAFstatus="";
			SAFdata=null;
			tmpdata = data;

			Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
			intent.addCategory(Intent.CATEGORY_OPENABLE);
			intent.addFlags(
					Intent.FLAG_GRANT_READ_URI_PERMISSION
							| Intent.FLAG_GRANT_WRITE_URI_PERMISSION
							| Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
							| Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
			intent.setType("*/*");
			launchIntent(intent, REGAIN_ACCESS_CODE);

		}else{
			writeFileContent( currentMAP, data);
		}
	}

	String tmpdata;
	byte[] bytedata;
	@Override
	public void saveasFile(String data, String filenamesuggestion)
	{
		tmpdata = data;
		SAFdata = null;
		SAFstatus="";
		Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.setType("*/*");
		intent.addFlags(
				Intent.FLAG_GRANT_READ_URI_PERMISSION
						| Intent.FLAG_GRANT_WRITE_URI_PERMISSION
						| Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
						| Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
		intent.putExtra(Intent.EXTRA_TITLE, filenamesuggestion);
		launchIntent(intent, CREATE_REQUEST_CODE);
	}

	@Override
	public void saveasFile(byte[] data, String filenamesuggestion)
	{
		bytedata = data;
		SAFdata = null;
		SAFstatus="";
		Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.setType("*/*");
		intent.addFlags(
				Intent.FLAG_GRANT_READ_URI_PERMISSION
						| Intent.FLAG_GRANT_WRITE_URI_PERMISSION
						| Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
						| Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
		intent.putExtra(Intent.EXTRA_TITLE, filenamesuggestion);
		launchIntent(intent, BINARY_CREATE_CODE);
	}


	java.util.List<byte[]> SAFdatas = new ArrayList<byte[]>();
	java.util.List<String> SAFfilenames = new ArrayList<String>();

	byte[] SAFdata;
	String SAFfilename="";
	String SAFuri="";
	volatile String SAFstatus="";

	@Override
	public void clearSAFstatus() {
		SAFstatus = "";
		SAFuri = "";
		SAFfilename = "";
		SAFdata = null;
		SAFdatas = null;
		SAFfilenames = null;
	}

	@Override
	public boolean saveToUri(String uriString, byte[] data) {
		if (uriString == null || uriString.isEmpty() || data == null) {
			return false;
		}
		try {
			return writeByte(Uri.parse(uriString), data);
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	@Override
	public boolean saveToFolderTree(String treeUri, String filename, byte[] data) {
		if (treeUri == null || treeUri.isEmpty() || filename == null || filename.isEmpty() || data == null) {
			return false;
		}
		try {
			Uri tree = Uri.parse(treeUri);
			// 目录中若已存在同名文件则直接覆盖写入，避免反复另建新文件
			Uri doc = findChildDocument( tree, filename );
			if (doc == null) {
				String docId = DocumentsContract.getTreeDocumentId(tree);
				Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
				doc = DocumentsContract.createDocument(getContentResolver(), parent, mimeFor(filename), filename);
			}
			if (doc == null) return false;
			return writeByte(doc, data);
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	// 在目录树中按文件名查找已有文件
	private Uri findChildDocument(Uri tree, String filename) {
		try {
			String docId = DocumentsContract.getTreeDocumentId( tree );
			Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree( tree, docId );
			Cursor c = getContentResolver().query( childrenUri,
					new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, OpenableColumns.DISPLAY_NAME},
					null, null, null );
			if (c != null) {
				try {
					while (c.moveToNext()) {
						String name = c.getString( c.getColumnIndex( OpenableColumns.DISPLAY_NAME ) );
						if (name != null && name.equalsIgnoreCase( filename ) ) {
							String id = c.getString( c.getColumnIndex( DocumentsContract.Document.COLUMN_DOCUMENT_ID ) );
							if (id != null) {
								return DocumentsContract.buildDocumentUriUsingTree( tree, id );
							}
						}
					}
				} finally {
					c.close();
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	private String mimeFor(String filename) {
		if (filename == null) return "application/octet-stream";
		String l = filename.toLowerCase();
		// 注意：.tmx/.tsx 虽然内容是 XML，但 Android MIME 表不认这两个后缀。
		// 若在此声明 application/xml，DocumentsProvider 会在文件名后自动补 ".xml"
		// （把 xxx.tmx 建成 xxx.tmx.xml）。故必须按普通文件处理以原样保留文件名。
		if (l.endsWith( ".xml" )) return "application/xml";
		if (l.endsWith( ".png" )) return "image/png";
		if (l.endsWith( ".jpg" ) || l.endsWith( ".jpeg" )) return "image/jpeg";
		if (l.endsWith( ".json" )) return "application/json";
		return "application/octet-stream";
	}

	private String folderDisplayName(Uri treeUri) {
		if (treeUri == null) return "";
		try {
			String docId = DocumentsContract.getTreeDocumentId( treeUri );
			String decoded = Uri.decode( docId );
			if (decoded != null) {
				if (decoded.startsWith( "primary:" )) {
					String sub = decoded.substring( "primary:".length() );
					if (sub.isEmpty()) return "/storage/emulated/0";
					return "/storage/emulated/0/" + sub;
				}
				return decoded;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return treeUri.toString();
	}

	private void takePersistable(Uri uri, Intent resultData) {
		if (uri == null || resultData == null) return;
		try {
			final int takeFlags = resultData.getFlags()
					& (Intent.FLAG_GRANT_READ_URI_PERMISSION
					| Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
			if (takeFlags != 0) {
				getContentResolver().takePersistableUriPermission(uri, takeFlags);
			}
		} catch (SecurityException e) {
			e.printStackTrace();
		}
	}

	@SuppressLint("WrongConstant")
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent resultData) {
		//mCheckout.onActivityResult(requestCode, resultCode, data);
		// 选择器已返回：无论成功/取消都解除「在途」标志，确保下一次点击能立即再次拉起。
		pickerInFlight = false;

		if (resultCode == RESULT_OK)
		{
			if (requestCode == CREATE_REQUEST_CODE)
			{
				if (resultData != null) {
					Uri uri = resultData.getData();
					writeFileContent( uri, tmpdata);
					SAFuri = uri.toString();
					SAFfilename = getFileName( uri );

					if (SAFfilename.endsWith( "tmx" )){
						currentMAP = uri;
						prefs.putString("url", currentMAP.toString());
						prefs.commit();
					}
					takePersistable( uri, resultData );
					SAFstatus="ok";

				}else{
					SAFstatus="cancel";
				}
			}
			if (requestCode == BINARY_CREATE_CODE)
			{
				if (resultData != null) {
					Uri uri = resultData.getData();
					writeByte( uri, bytedata);
					SAFuri = uri.toString();
					SAFfilename = getFileName( uri );
					if (SAFfilename.endsWith( "tmx" )){
						currentMAP = uri;
						prefs.putString("url", currentMAP.toString());
						prefs.commit();
					}
					takePersistable( uri, resultData );
					SAFstatus="ok";
				}else{
					SAFstatus="cancel";
				}
			}
			else if (requestCode == SAVEAS_REQUEST_CODE) {

				if (resultData != null) {
					currentMAP = resultData.getData();
					SAFuri = currentMAP.toString();
					SAFfilename = getFileName( currentMAP );
					takePersistable( currentMAP, resultData );
					writeFileContent( currentMAP, "test");
					SAFstatus="ok";
				}else{
					SAFstatus="cancel";
				}
			}
			else if (requestCode == REQUEST_TREE_CODE) {

				if (resultData != null) {
					// 整个文件夹的枚举与内容读取放到后台线程执行，避免在 UI 线程同步读入
					// 大量文件导致从系统选择器返回后出现卡屏 / ANR。
					SAFstatus = "";
					SAFuri = "";
					SAFfilename = "";
					SAFdatas = null;
					SAFfilenames = null;
					final Uri tree = resultData.getData();
					new Thread( new Runnable() {
						@Override
						public void run() {
							try {
								java.util.List<Uri> docs = readFiles( MainActivity.this, resultData );
								java.util.List<byte[]> datas = new ArrayList<byte[]>();
								java.util.List<String> names = new ArrayList<String>();
								for (Uri u : docs) {
									try {
										byte[] b = readBytes( u );
										String s = getFileName( u );
										if (b != null && s != null) {
											datas.add( b );
											names.add( s );
										}
									} catch (Exception e) {
										// 单个文件读取失败不中断，继续尝试下一个
									}
								}
								SAFdatas = datas;
								SAFfilenames = names;
								takePersistable( tree, resultData );
								SAFuri = tree.toString();
								SAFfilename = folderDisplayName( tree );
								SAFstatus = "ok";
							} catch (Exception e) {
								e.printStackTrace();
								SAFstatus = "error";
							}
						}
					} ).start();
				}else{
					SAFstatus = "cancel";
				}
			}
			else if (requestCode == REQUEST_TREE_ONLY_CODE) {

				if (resultData != null) {
					takePersistable( resultData.getData(), resultData );
					SAFuri = resultData.getData().toString();
					SAFfilename = folderDisplayName( resultData.getData() );
					SAFstatus = "ok";
				}else{
					SAFstatus = "cancel";
				}
			}
			else if (requestCode == OPEN_REQUEST_CODE) {

				if (resultData != null) {

					try {
						Uri uri = resultData.getData();
						SAFdata = readBytes( uri );
						SAFfilename = getFileName( uri );
						SAFuri = uri.toString();

						if (SAFfilename.endsWith( "tmx" )){
							currentMAP = uri;
							prefs.putString("url", currentMAP.toString());
							prefs.commit();
						}
						takePersistable( uri, resultData );
						SAFstatus = "ok";
					} catch (IOException e) {
						SAFstatus = "error";
					}
				}else{
					SAFstatus = "cancel";
				}
			} else if (requestCode == REGAIN_ACCESS_CODE) {

				if (resultData != null) {
					currentMAP = resultData.getData();
					SAFuri = currentMAP.toString();
					SAFfilename = getFileName( currentMAP );
					takePersistable( currentMAP, resultData );
					writeFileContent( currentMAP, tmpdata);
					SAFstatus="ok";
				}else{
					SAFstatus="cancel";
				}
			}else{
			}

		}else{
			if (requestCode == CREATE_REQUEST_CODE
					|| requestCode == BINARY_CREATE_CODE
					|| requestCode == SAVEAS_REQUEST_CODE
					|| requestCode == REQUEST_TREE_CODE
					|| requestCode == REQUEST_TREE_ONLY_CODE
					|| requestCode == OPEN_REQUEST_CODE
					|| requestCode == REGAIN_ACCESS_CODE) {
				SAFstatus = "cancel";
			}
		}
		super.onActivityResult(requestCode, resultCode, resultData);
	}

	private boolean writeFileContent(Uri uri, String data)
	{
		ParcelFileDescriptor pfd = null;
		FileOutputStream fileOutputStream = null;
		try{
			pfd = this.getContentResolver().openFileDescriptor(uri, "rwt");
			if (pfd == null) return false;
			fileOutputStream = new FileOutputStream(pfd.getFileDescriptor());
			fileOutputStream.write(data.getBytes());
			fileOutputStream.flush();
			return true;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		} finally {
			try { if (fileOutputStream != null) fileOutputStream.close(); } catch (Exception ignore) {}
			try { if (pfd != null) pfd.close(); } catch (Exception ignore) {}
		}
	}

	private boolean writeByte(Uri uri, byte[] data)
	{
		ParcelFileDescriptor pfd = null;
		FileOutputStream fileOutputStream = null;
		try{
			pfd = this.getContentResolver().openFileDescriptor(uri, "rwt");
			if (pfd == null) return false;
			fileOutputStream = new FileOutputStream(pfd.getFileDescriptor());
			fileOutputStream.write( data );
			fileOutputStream.flush();
			return true;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		} finally {
			try { if (fileOutputStream != null) fileOutputStream.close(); } catch (Exception ignore) {}
			try { if (pfd != null) pfd.close(); } catch (Exception ignore) {}
		}
	}



	private byte[] readBytes(Uri uri) throws IOException {

		InputStream inputStream =
				getContentResolver().openInputStream(uri);


		ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
		int bufferSize = 1024;
		byte[] buffer = new byte[bufferSize];

		int len = 0;

		while ((len = inputStream.read(buffer)) != -1) {
			byteBuffer.write(buffer, 0, len);
		}
		inputStream.close();
		return byteBuffer.toByteArray();
	}


	private String readFileContent(Uri uri) throws IOException {

		InputStream inputStream =
				getContentResolver().openInputStream(uri);
		BufferedReader reader =
				new BufferedReader(new InputStreamReader(
						inputStream));
		StringBuilder stringBuilder = new StringBuilder();
		String currentline;
		while ((currentline = reader.readLine()) != null) {
			stringBuilder.append(currentline + "\n");
		}
		inputStream.close();
		return stringBuilder.toString();
	}

	public String getFileName(Uri uri) {
		String result = null;
		if (uri.getScheme().equals("content")) {
			Cursor cursor = getContentResolver().query(uri, null, null, null, null);
			try {
				if (cursor != null && cursor.moveToFirst()) {
					int dx = cursor.getColumnIndex( OpenableColumns.DISPLAY_NAME);
					if(dx!=-1) result = cursor.getString(dx);
				}
			} finally {
				cursor.close();
			}
		}
		if (result == null) {
			result = uri.getPath();
			int cut = result.lastIndexOf('/');
			if (cut != -1) {
				result = result.substring(cut + 1);
			}
		}
		return result;
	}

	@TargetApi(Build.VERSION_CODES.LOLLIPOP)
	private List<Uri> readFiles(Context context, Intent intent) {
		return readFiles( intent.getData() );
	}

	@TargetApi(Build.VERSION_CODES.LOLLIPOP)
	private List<Uri> readFiles(Uri uriTree) {
		List<Uri> uriList = new ArrayList<>();

		if (uriTree == null) return uriList;
		// the uri from which we query the files
		Uri uriFolder = DocumentsContract.buildChildDocumentsUriUsingTree(uriTree, DocumentsContract.getTreeDocumentId(uriTree));

		Cursor cursor = null;
		try {
			// let's query the files
			cursor = getContentResolver().query(uriFolder,
					new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},
					null, null, null);

			if (cursor != null && cursor.moveToFirst()) {
				do {
					// build the uri for the file
					Uri uriFile = DocumentsContract.buildDocumentUriUsingTree(uriTree, cursor.getString(0));
					//add to the list
					uriList.add(uriFile);

				} while (cursor.moveToNext());
			}

		} catch (Exception e) {
			// TODO: handle error
		} finally {
			if (cursor!=null) cursor.close();
		}

		//return the list
		return uriList;
	}

	// ============================================================
	//  自动更新（平台相关实现）
	// ============================================================
	@Override
	public int getVersionCode() {
		try {
			PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
			return pi.versionCode;
		} catch (Exception e) {
			return 0;
		}
	}

	// 后台线程下载 APK 到私有目录 filesDir/update/<saveName>，进度回调切回 UI 线程。
	@Override
	public void downloadApk(final String url, final String saveName, final Interface.ApkDownloader cb) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				java.net.HttpURLConnection conn = null;
				FileOutputStream fos = null;
				try {
					java.io.File dir = new java.io.File(getFilesDir(), ApkFileProvider.DIR);
					if (!dir.exists()) dir.mkdirs();
					java.io.File out = new java.io.File(dir, saveName);
					conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
					conn.setConnectTimeout(15000);
					conn.setReadTimeout(30000);
					conn.connect();
					int code = conn.getResponseCode();
					if (code != 200) {
						postDownloadError(cb, "服务器返回 HTTP " + code);
						return;
					}
					long total = conn.getContentLength();   // 可能为 -1（无 Content-Length）
					InputStream in = conn.getInputStream();
					fos = new FileOutputStream(out);
					byte[] buf = new byte[64 * 1024];
					long done = 0;
					int n;
					int lastPct = -1;
					while ((n = in.read(buf)) > 0) {
						fos.write(buf, 0, n);
						done += n;
						if (total > 0) {
							int pct = (int) (done * 100 / total);
							if (pct != lastPct) {
								lastPct = pct;
								postDownloadProgress(cb, pct);
							}
						}
					}
					fos.flush();
					fos.close();
					fos = null;
					in.close();
					postDownloadSuccess(cb, out.getAbsolutePath());
				} catch (Exception e) {
					postDownloadError(cb, "下载失败: " + e.getMessage());
				} finally {
					try { if (fos != null) fos.close(); } catch (Exception ignore) {}
					try { if (conn != null) conn.disconnect(); } catch (Exception ignore) {}
				}
			}
		}, "ApkDownload").start();
	}

	private void postDownloadProgress(final Interface.ApkDownloader cb, final int pct) {
		if (cb == null) return;
		runOnUiThread(new Runnable() { public void run() { cb.onProgress(pct); } });
	}

	private void postDownloadSuccess(final Interface.ApkDownloader cb, final String path) {
		if (cb == null) return;
		runOnUiThread(new Runnable() { public void run() { cb.onSuccess(path); } });
	}

	private void postDownloadError(final Interface.ApkDownloader cb, final String msg) {
		if (cb == null) return;
		runOnUiThread(new Runnable() { public void run() { cb.onError(msg); } });
	}

	// 返回可写入更新 APK 的私有路径（filesDir/update/<name>），供 OTA 分块下载后拉起安装器。
	@Override
	public String getUpdateFilePath(String name) {
		try {
			java.io.File dir = new java.io.File(getFilesDir(), ApkFileProvider.DIR);
			if (!dir.exists()) dir.mkdirs();
			return new java.io.File(dir, name).getAbsolutePath();
		} catch (Exception e) {
			return null;
		}
	}

	// 在本地查找已下载的更新包：先查私有 update 目录，再查公共“下载/Tunit”。
	// 命中后确保文件位于私有 update 目录（FileProvider 仅共享该目录），返回可安装的私有绝对路径；未命中返回 null。
	@Override
	public String findLocalUpdateApk(final String name, final long size) {
		try {
			java.io.File priv = new java.io.File(getFilesDir(), ApkFileProvider.DIR);
			java.io.File hit = searchApkInDir(priv, name, size);
			if (hit != null) return hit.getAbsolutePath();

			java.io.File pub = queryPublicApk(name, size);
			if (pub != null) {
				java.io.File dst = ensureInUpdateDir(pub);
				if (dst != null) return dst.getAbsolutePath();
			}
		} catch (Exception ignore) {}
		return null;
	}

	// 在系统文件管理器中定位到指定目录（供无法自动安装时手动安装）。
	@Override
	public void openFolder(final String dirPath) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				String docId = null;
				try {
					if (dirPath != null) {
						String p = dirPath.replace('\\', '/');
						int i = p.indexOf("/Download/");
						if (i >= 0) {
							docId = "primary:" + p.substring(i + 1);   // Download/Tunit
						} else if (p.contains("/emulated/0/")) {
							docId = "primary:" + p.substring(p.indexOf("/emulated/0/") + "/emulated/0/".length());
						}
					}
				} catch (Exception ignore) {}
				if (docId == null) docId = "primary:Download/Tunit";
				// 优先用 DocumentsUI 定位到该目录
				try {
					Uri uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId);
					Intent it = new Intent(Intent.ACTION_VIEW);
					it.setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR);
					it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
					startActivity(it);
					return;
				} catch (Exception ignore) {}
				// 回退：打开文件管理器中的内部存储根目录
				try {
					Uri uri = DocumentsContract.buildRootUri("com.android.externalstorage.documents", "primary");
					Intent it = new Intent(Intent.ACTION_VIEW);
					it.setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR);
					startActivity(it);
					return;
				} catch (Exception ignore) {}
				android.widget.Toast.makeText(MainActivity.this,
						"请在文件管理器中打开目录：下载/Tunit",
						android.widget.Toast.LENGTH_LONG).show();
			}
		});
	}

	// 在指定目录内查找 apk：优先精确文件名匹配，其次任意 .apk；size>0 时要求大小一致。
	private java.io.File searchApkInDir(java.io.File dir, String name, long size) {
		if (dir == null || !dir.isDirectory()) return null;
		java.io.File[] files = dir.listFiles();
		if (files == null) return null;
		String want = (name == null) ? "" : name.trim();
		if (!want.isEmpty()) {
			for (java.io.File f : files) {
				if (f != null && f.isFile() && f.getName().equalsIgnoreCase(want)
						&& (size <= 0 || f.length() == size)) {
					return f;
				}
			}
		}
		for (java.io.File f : files) {
			if (f != null && f.isFile() && f.getName().toLowerCase().endsWith(".apk")
					&& (size <= 0 || f.length() == size)) {
				return f;
			}
		}
		return null;
	}

	// 在公共“下载/Tunit”中查找 apk：API 29+ 走 MediaStore 查询，旧版本直接遍历目录。
	private java.io.File queryPublicApk(String name, long size) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			for (java.io.File d : publicUpdateDirs()) {
				java.io.File f = searchApkInDir(d, name, size);
				if (f != null) return f;
			}
			return null;
		}
		Cursor c = null;
		try {
			String[] proj = new String[]{
					android.provider.MediaStore.MediaColumns.DATA,
					android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
					android.provider.MediaStore.MediaColumns.SIZE
			};
			String sel = android.provider.MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
			c = getContentResolver().query(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
					proj, sel, new String[]{ "Download/Tunit%" }, null);
			if (c != null) {
				String want = (name == null) ? "" : name.trim();
				String fallback = null;
				while (c.moveToNext()) {
					String dn = c.getString(1);
					String data = c.getString(0);
					long sz = c.getLong(2);
					if (dn == null || !dn.toLowerCase().endsWith(".apk")) continue;
					if (data == null || data.isEmpty()) continue;
					if (size > 0 && sz > 0 && sz != size) continue;
					if (!want.isEmpty() && dn.equalsIgnoreCase(want)) return new java.io.File(data);
					if (fallback == null) fallback = data;
				}
				if (fallback != null) return new java.io.File(fallback);
			}
		} catch (Exception ignore) {
		} finally {
			try { if (c != null) c.close(); } catch (Exception ignore) {}
		}
		return null;
	}

	// 确保 apk 位于私有 update 目录（FileProvider 只能共享该目录），返回私有路径；失败返回 null。
	private java.io.File ensureInUpdateDir(java.io.File apk) {
		if (apk == null || !apk.exists()) return null;
		try {
			java.io.File dir = new java.io.File(getFilesDir(), ApkFileProvider.DIR);
			if (!dir.exists()) dir.mkdirs();
			java.io.File dst = new java.io.File(dir, apk.getName());
			if (apk.getAbsolutePath().equals(dst.getAbsolutePath())) return dst;
			if (dst.exists() && dst.length() == apk.length()) return dst;
			copyFile(apk, dst);
			return dst.exists() ? dst : null;
		} catch (Exception e) {
			return null;
		}
	}

	// 复制文件（失败抛 IOException）。
	private void copyFile(java.io.File src, java.io.File dst) throws IOException {
		java.io.FileInputStream in = new java.io.FileInputStream(src);
		java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
		try {
			byte[] buf = new byte[64 * 1024];
			int n;
			while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
			out.flush();
		} finally {
			try { in.close(); } catch (Exception ignore) {}
			try { out.close(); } catch (Exception ignore) {}
		}
	}

	// 拉起系统安装器安装已下载的 APK（Android 8.0+ 需先授权“安装未知应用”）。
	// 先做包名/签名/版本兼容性检测：不兼容则镜像到公共目录并提示可手动安装。
	@Override
	public void installApk(final String absolutePath) {
		final String path = absolutePath;
		new Thread(new Runnable() {
			@Override
			public void run() {
				final java.io.File apk = new java.io.File(path == null ? "" : path);
				if (!apk.exists()) {
					toastUi("更新文件不存在：" + path);
					return;
				}
				String pkName = getApkPackageName(path);
				long installedVc = getInstalledVersionCode();
				long incomingVc = getApkVersionCode(path);
				boolean samePkg = pkName != null && pkName.equals(getPackageName());
				boolean sigOk = !samePkg || isApkSignatureCompatible(path);   // 不同包名可并存安装，无需签名一致
				boolean downgrade = samePkg && incomingVc > 0 && installedVc > 0 && incomingVc <= installedVc;
				// 无法解析包信息（文件损坏/非法 APK）：不走安装器，直接镜像到公共目录并提示手动处理
				boolean broken = (pkName == null) || (incomingVc <= 0);
				// 无论是否可覆盖，都镜像一份到公共“下载/Tunit”，便于日后手动重装
				final String mirror = saveUpdateApkToPublicFolder(apk);
				if (broken || (samePkg && (!sigOk || downgrade))) {
					final String reason = broken ? "安装包无法解析（可能已损坏或不完整），无法自动安装"
							: ((!sigOk) ? "安装包签名与已安装版本不一致，系统会拒绝覆盖安装" : "安装包版本号未高于当前版本，系统会拒绝覆盖安装");
					final String savedPath = mirror;
					runOnUiThread(new Runnable() {
						@Override
						public void run() { showInstallFallback(reason, savedPath, path); }
					});
					return;
				}
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						try {
							if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
								android.widget.Toast.makeText(MainActivity.this, "请允许本应用“安装未知应用”，返回后再试一次更新", android.widget.Toast.LENGTH_LONG).show();
								Intent set = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
								set.setData(Uri.parse("package:" + getPackageName()));
								startActivity(set);
								return;
							}
							java.io.File inDir = ensureInUpdateDir(apk);
							String shareName = (inDir != null) ? inDir.getName() : apk.getName();
							Uri uri = ApkFileProvider.uriFor(shareName);
							Intent it = new Intent(Intent.ACTION_VIEW);
							it.setDataAndType(uri, "application/vnd.android.package-archive");
							it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
							startActivity(it);
						} catch (Exception e) {
							showInstallFallback("拉起安装器失败：" + e.getMessage(), mirror, path);
							e.printStackTrace();
						}
					}
				});
			}
		}, "ApkInstall").start();
	}

	// 读取 APK 文件内声明的包名（失败返回 null）。
	private String getApkPackageName(String apkPath) {
		try {
			PackageInfo pi = getPackageManager().getPackageArchiveInfo(apkPath, 0);
			return (pi == null) ? null : pi.packageName;
		} catch (Exception e) {
			return null;
		}
	}

	// 判断 APK 签名是否与已安装应用兼容（存在交集即兼容）。无法获取签名信息时返回 true（宁可尝试安装）。
	private boolean isApkSignatureCompatible(String apkPath) {
		try {
			PackageManager pm = getPackageManager();
			PackageInfo installed = pm.getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
			PackageInfo archive = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNATURES);
			if (installed == null || archive == null || installed.signatures == null || archive.signatures == null) return true;
			if (installed.signatures.length == 0 || archive.signatures.length == 0) return true;
			java.util.Set<String> a = new java.util.HashSet<String>();
			for (android.content.pm.Signature s : installed.signatures) a.add(s.toCharsString());
			for (android.content.pm.Signature s : archive.signatures) {
				if (a.contains(s.toCharsString())) return true;
			}
			return false;
		} catch (Exception e) {
			return true;
		}
	}

	// 无法自动安装时的提示：说明原因，并提供“打开文件夹手动安装”。
	private void showInstallFallback(String reason, final String savedPath, final String apkPath) {
		try {
			final String dir = (savedPath != null && savedPath.contains("/"))
					? savedPath.substring(0, savedPath.lastIndexOf('/')) : null;
			String msg = reason + "。";
			msg += (savedPath == null) ? "\n（未能复制到公共目录，请在应用私有目录查找）" : ("\n安装包已复制到：\n" + savedPath);
			new android.app.AlertDialog.Builder(MainActivity.this)
					.setTitle("无法自动安装")
					.setMessage(msg)
					.setPositiveButton("打开文件夹", new android.content.DialogInterface.OnClickListener() {
						@Override
						public void onClick(android.content.DialogInterface d, int w) { openFolder(dir); }
					})
					.setNeutralButton("仍要尝试安装", new android.content.DialogInterface.OnClickListener() {
						@Override
						public void onClick(android.content.DialogInterface d, int w) { forceInstall(apkPath); }
					})
					.setNegativeButton("取消", null)
					.show();
		} catch (Exception e) {
			android.widget.Toast.makeText(MainActivity.this, reason + (savedPath == null ? "" : ("\n安装包位于：" + savedPath)), android.widget.Toast.LENGTH_LONG).show();
		}
	}

	// 忽略兼容性检测，直接拉起系统安装器（供用户坚持尝试）。
	private void forceInstall(final String apkPath) {
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
				Intent set = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
				set.setData(Uri.parse("package:" + getPackageName()));
				startActivity(set);
				return;
			}
			java.io.File apk = new java.io.File(apkPath);
			java.io.File inDir = ensureInUpdateDir(apk);
			String shareName = (inDir != null) ? inDir.getName() : apk.getName();
			Uri uri = ApkFileProvider.uriFor(shareName);
			Intent it = new Intent(Intent.ACTION_VIEW);
			it.setDataAndType(uri, "application/vnd.android.package-archive");
			it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
			startActivity(it);
		} catch (Exception e) {
			android.widget.Toast.makeText(MainActivity.this, "拉起安装器失败：" + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
		}
	}

	private void toastUi(final String msg) {
		runOnUiThread(new Runnable() { public void run() {
			android.widget.Toast.makeText(MainActivity.this, msg, android.widget.Toast.LENGTH_LONG).show();
		} });
	}

	// 读取当前已安装应用的 versionCode（失败返回 0）。
	private long getInstalledVersionCode() {
		try {
			android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
			return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ? pi.getLongVersionCode() : pi.versionCode;
		} catch (Exception e) {
			return 0L;
		}
	}

	// 读取指定 APK 文件内部声明的 versionCode（失败返回 0）。
	private long getApkVersionCode(String apkPath) {
		try {
			android.content.pm.PackageInfo pi = getPackageManager().getPackageArchiveInfo(apkPath, 0);
			if (pi == null) return 0L;
			return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ? pi.getLongVersionCode() : pi.versionCode;
		} catch (Exception e) {
			return 0L;
		}
	}

	// 把下载的更新包复制到用户可见文件夹，返回目标路径。
	// API 29+ 用 MediaStore 写入公共“下载/Tunit”（文件管理器中可见）；失败回退应用外部目录。
	private String saveUpdateApkToPublicFolder(java.io.File apk) {
		if (apk == null || !apk.exists()) return null;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			String p = saveViaMediaStore(apk);
			if (p != null) return p;
		}
		for (java.io.File dir : publicUpdateDirs()) {
			try {
				if (!dir.exists()) dir.mkdirs();
				if (!dir.isDirectory()) continue;
				java.io.File dst = new java.io.File(dir, apk.getName());
				copyFile(apk, dst);
				return dst.getAbsolutePath();
			} catch (Exception ignore) {
				// 该目录不可写，尝试下一个
			}
		}
		return null;
	}

	// 用 MediaStore 把文件写入公共“下载/Tunit”，返回其绝对路径（无法获取路径时返回 content uri 字符串）。
	private String saveViaMediaStore(java.io.File apk) {
		Uri uri = null;
		try {
			// 先删除同名旧条目，避免系统自动重命名成 “xxx (1).apk”
			try {
				String sel = android.provider.MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND "
						+ android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=?";
				getContentResolver().delete(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
						sel, new String[]{ "Download/Tunit%", apk.getName() });
			} catch (Exception ignore) {}

			android.content.ContentValues cv = new android.content.ContentValues();
			cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, apk.getName());
			cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive");
			cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Tunit");
			uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
			if (uri == null) return null;
			java.io.OutputStream os = getContentResolver().openOutputStream(uri);
			java.io.FileInputStream in = new java.io.FileInputStream(apk);
			try {
				byte[] buf = new byte[64 * 1024];
				int n;
				while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
				os.flush();
			} finally {
				try { in.close(); } catch (Exception ignore) {}
				try { os.close(); } catch (Exception ignore) {}
			}
			return queryDataPath(uri);
		} catch (Exception e) {
			if (uri != null) { try { getContentResolver().delete(uri, null, null); } catch (Exception ignore) {} }
			return null;
		}
	}

	// 查询 content uri 对应的绝对路径（MediaStore DATA 列，API 29+ 仍返回真实路径）。
	private String queryDataPath(Uri uri) {
		Cursor c = null;
		try {
			c = getContentResolver().query(uri, new String[]{ android.provider.MediaStore.MediaColumns.DATA }, null, null, null);
			if (c != null && c.moveToFirst()) {
				String p = c.getString(0);
				if (p != null && !p.isEmpty()) return p;
			}
		} catch (Exception ignore) {
		} finally {
			try { if (c != null) c.close(); } catch (Exception ignore) {}
		}
		return uri.toString();
	}

	// 可能作为更新包存放的公共/外部目录候选。
	private java.util.List<java.io.File> publicUpdateDirs() {
		java.util.List<java.io.File> dirs = new java.util.ArrayList<java.io.File>();
		try {
			java.io.File pub = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
			if (pub != null) dirs.add(new java.io.File(pub, "Tunit"));
		} catch (Throwable ignore) {}
		try {
			java.io.File ext = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
			if (ext != null) dirs.add(ext);
		} catch (Throwable ignore) {}
		return dirs;
	}

	// 启动时检测是否存在“已下载未安装”的更新包。仅做静默记录，不再主动跳转“安装未知应用”设置页
	// （避免每次进入软件被打扰）；真正的授权引导放在用户点“立即更新”后由 installApk() 按需触发。
	private void checkPendingUpdatePermission() {
		try {
			java.io.File dir = new java.io.File(getFilesDir(), ApkFileProvider.DIR);
			java.io.File[] apks = dir.listFiles(new java.io.FileFilter() {
				@Override
				public boolean accept(java.io.File f) {
					return f != null && f.isFile() && f.getName().toLowerCase().endsWith(".apk");
				}
			});
			if (apks != null && apks.length > 0) {
				android.util.Log.i("OTA", "检测到 " + apks.length + " 个已下载未安装的更新包（启动不打扰，待用户主动更新时引导授权）");
			}
		} catch (Exception ignore) {}
	}
}
