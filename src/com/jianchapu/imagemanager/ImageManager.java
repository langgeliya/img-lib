package com.jianchapu.imagemanager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

public class ImageManager {
	private final static String TAG = "ImageManager";
	
	private ImageMemoryCache imageMemoryCache; //内存缓存
	
	private ImageFileCache   imageFileCache; //文件缓存
	
	//正在下载的image列表
	public static HashMap<String, Handler> ongoingTaskMap = new HashMap<String, Handler>();
	
	//等待下载的image列表
	public static HashMap<String, Handler> waitingTaskMap = new HashMap<String, Handler>();
	
	//同时下载图片的线程个数
	final static int MAX_DOWNLOAD_IMAGE_THREAD = 4;
	
	private final Handler downloadStatusHandler = new Handler(){
		public void handleMessage(Message msg)
		{
			startDownloadNext();
		}
	};
	
	private static class ImageManagerInstance{
		private static final ImageManager INSTANCE = new ImageManager(); 
	}
	
	private ImageManager()
	{
		imageMemoryCache = new ImageMemoryCache();
		imageFileCache = new ImageFileCache();
    }
	
	public static ImageManager getInstance() {
		return ImageManagerInstance.INSTANCE;
	}
	
    /**
     * 获取图片，多线程的入口
     */
    public void loadBitmap(String url, Handler handler) 
    {
        //先从内存缓存中获取，取到直接加载
        Bitmap bitmap = getBitmapFromNative(url);
        
        if (bitmap != null)
        {
//            Logger.d(TAG, "loadBitmap:loaded from native");
        	Message msg = Message.obtain();
            Bundle bundle = new Bundle();
            bundle.putString("url", url);
            msg.obj = bitmap;
            msg.setData(bundle);
            handler.sendMessage(msg);
        } 
        else
        {
//        	Logger.d(TAG, "loadBitmap:will load by network");
        	downloadBmpOnNewThread(url, handler);
        }
    }
    
    /**
     * 新起线程下载图片
     */
    private void downloadBmpOnNewThread(final String url, final Handler handler)
    {
//		Logger.d(TAG, "ongoingTaskMap'size=" + ongoingTaskMap.size());
    	
		if (ongoingTaskMap.size() >= MAX_DOWNLOAD_IMAGE_THREAD) 
		{
			synchronized (waitingTaskMap) 
			{
				waitingTaskMap.put(url, handler);
			}
		} 
		else 
		{
			synchronized (ongoingTaskMap) 
			{
				ongoingTaskMap.put(url, handler);
			}

			new Thread() 
			{
				public void run() 
				{
					Bitmap bmp = getBitmapFromHttp(url);

					// 不论下载是否成功，都从下载队列中移除,再由业务逻辑判断是否重新下载
					// 下载图片使用了httpClientRequest，本身已经带了重连机制
					synchronized (ongoingTaskMap) 
					{
						ongoingTaskMap.remove(url);
					}
					
					if(downloadStatusHandler != null)
					{
						downloadStatusHandler.sendEmptyMessage(0);
					
					}

					Message msg = Message.obtain();
					msg.obj = bmp;
					Bundle bundle = new Bundle();
					bundle.putString("url", url);
					msg.setData(bundle);
					
					if(handler != null)
					{
						handler.sendMessage(msg);
					}

				}
			}.start();
		}
	}

    
	/**
     * 依次从内存，缓存文件，网络上加载单个bitmap,不考虑线程的问题
     */
	public Bitmap getBitmap(String url)
	{
	    // 从内存缓存中获取图片
	    Bitmap bitmap = imageMemoryCache.getBitmapFromMemory(url);
	    if (bitmap == null) 
	    {
	        // 文件缓存中获取
	    	bitmap = imageFileCache.getImageFromFile(url);
	        if (bitmap != null) 
	        {	            
	        	// 添加到内存缓存
	        	imageMemoryCache.addBitmapToMemory(url, bitmap);
	        } 
	        else 
	        {
	            // 从网络获取
	        	bitmap = getBitmapFromHttp(url);
	        }
	    }
	    return bitmap;
	}
	
	/**
	 * 从内存或者缓存文件中获取bitmap
	 */
	public Bitmap getBitmapFromNative(String url)
	{
		Bitmap bitmap = null;
		bitmap = imageMemoryCache.getBitmapFromMemory(url);
		
		if(bitmap == null)
		{
			bitmap = imageFileCache.getImageFromFile(url);
			if(bitmap != null)
			{
				// 添加到内存缓存
				imageMemoryCache.addBitmapToMemory(url, bitmap);
			}
		}
		return bitmap;
	}
	
	/**
	 * 通过网络下载图片,与线程无关
	 */
	public Bitmap getBitmapFromHttp(String url)
	{
		Bitmap bmp = null;
		
		try
		{
			byte[] tmpPicByte = getImageBytes(url);
	
			if (tmpPicByte != null) 
			{
				bmp = BitmapFactory.decodeByteArray(tmpPicByte, 0,
						tmpPicByte.length);
			}
			tmpPicByte = null;
		}
		catch(Exception e)
		{
			e.printStackTrace();
		}
		
		if(bmp != null)
		{
			// 添加到文件缓存
			imageFileCache.saveBitmapToFile(bmp, url);
			// 添加到内存缓存
			imageMemoryCache.addBitmapToMemory(url, bmp);
		}

		return bmp;
	}
	
	/**
	 * 下载链接的图片资源
	 * 
	 * @param url
	 *            
	 * @return 图片
	 */
	public byte[] getImageBytes(String url) 
	{
		byte[] pic = null;
		if (url != null && !"".equals(url)) 
		{
//			Requester request = RequesterFactory.getRequester(
//					Requester.REQUEST_REMOTE, RequesterFactory.IMPL_HC);
//			// 执行请求
//			MyResponse myResponse = null;
//			MyRequest mMyRequest;
//			mMyRequest = new MyRequest();
//			mMyRequest.setUrl(url);
//			mMyRequest.addHeader(HttpHeader.REQ.ACCEPT_ENCODING, "identity");
			HttpGet httpGet = new HttpGet(url);
			DefaultHttpClient httpClient = new DefaultHttpClient(); 
			InputStream is = null;
			ByteArrayOutputStream baos = null;
			HttpResponse httpResponse = null;
			int resultCode = 0;
			try {
//				myResponse = request.execute(mMyRequest);
				httpResponse = httpClient.execute(httpGet);
				resultCode = httpResponse.getStatusLine().getStatusCode();
				if(resultCode == HttpStatus.SC_OK){
					is = httpResponse.getEntity().getContent();
					baos = new ByteArrayOutputStream();
					byte[] b = new byte[512];
					int len = 0;
					while ((len = is.read(b)) != -1) 
					{
						baos.write(b, 0, len);
						baos.flush();
					}
					pic = baos.toByteArray();
				}
				
//				Logger.d(TAG, "icon bytes.length=" + pic.length);

			} 
			catch (Exception e3) 
			{
				e3.printStackTrace();
				try 
				{
					Log.v("mj", "download shortcut icon faild and responsecode="
									+ httpResponse.getStatusLine().getStatusCode());
//					Logger.e(TAG,
//							"download shortcut icon faild and responsecode="
//									+ httpResponse.getStatusLine().getStatusCode());
				} 
				catch (Exception e4) 
				{
					e4.printStackTrace();
				}
			} 
			finally 
			{
				try 
				{
					if (is != null) 
					{
						is.close();
						is = null;
					}
				} 
				catch (Exception e2) 
				{
					e2.printStackTrace();
				}
				try 
				{
					if (baos != null) 
					{
						baos.close();
						baos = null;
					}
				} 
				catch (Exception e2) 
				{
					e2.printStackTrace();
				}
				try 
				{
//					http.close();
				} 
				catch (Exception e1) 
				{
					e1.printStackTrace();
				}
			}
		}
		return pic;
	}
	
	/**
	 * 取出等待队列第一个任务，开始下载
	 */
	private void startDownloadNext()
	{
		synchronized(waitingTaskMap)
		{	
//			Logger.d(TAG, "begin start next");
			Iterator iter = waitingTaskMap.entrySet().iterator(); 
		
			while (iter.hasNext()) 
			{
				
				Map.Entry entry = (Map.Entry) iter.next();
//				Logger.d(TAG, "WaitingTaskMap isn't null,url=" + (String)entry.getKey());
				
				if(entry != null)
				{
					waitingTaskMap.remove(entry.getKey());
					downloadBmpOnNewThread((String)entry.getKey(), (Handler)entry.getValue());
				}
				break;
			}
		}
	}
	
	public String startDownloadNext_ForUnitTest()
	{
		String urlString = null;
		synchronized(waitingTaskMap)
		{
//			Logger.d(TAG, "begin start next");
			Iterator iter = waitingTaskMap.entrySet().iterator(); 
		
			while (iter.hasNext()) 
			{
				Map.Entry entry = (Map.Entry) iter.next();
				urlString = (String)entry.getKey();
				waitingTaskMap.remove(entry.getKey());
				break;
			}
		}
		return urlString;
	}
	
	/**
	 * 图片变为圆角
	 * @param bitmap:传入的bitmap
	 * @param pixels：圆角的度数，值越大，圆角越大
	 * @return bitmap:加入圆角的bitmap
	 */
	@SuppressWarnings("deprecation")
	public static Bitmap toRoundCorner(Bitmap bitmap, int pixels) 
	{ 
        if(bitmap == null)
        	return null;
        
		Bitmap output = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Config.ARGB_8888); 
        Canvas canvas = new Canvas(output); 
 
        final int color = 0xff424242; 
        final Paint paint = new Paint(); 
        final Rect rect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()); 
        final RectF rectF = new RectF(rect); 
        final float roundPx = pixels; 
 
        paint.setAntiAlias(true); 
        canvas.drawARGB(0, 0, 0, 0); 
        paint.setColor(color); 
        canvas.drawRoundRect(rectF, roundPx, roundPx, paint); 
 
        paint.setXfermode(new PorterDuffXfermode(Mode.SRC_IN)); 
        canvas.drawBitmap(bitmap, rect, rect, paint); 
 
        return output; 
    }
	
//	public byte managerId() 
//	{
//		return IMAGE_ID;
//	}
}
