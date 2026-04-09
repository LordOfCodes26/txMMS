package com.chutils.emo.manager;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.chutils.CHGlobal;
import com.chutils.CHLog;
import com.chutils.api.ResUtilsApi;
import com.chutils.emo.model.EmoModel;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

//bigbug128
public class ResManager extends ResUtilsApi {
	private static final String TAG = ResManager.class.getSimpleName();

	public static final int BYTE_EMOICO_START_1 = 0xF0;
	public static final int BYTE_EMOICO_START_2 = 0xE2;
	public static final int BYTE_EMOICO_START_3 = 0xE3;
	public static final int BYTE_EMO_HEADER_START = 0x60;

	public static final int EMOICO_TYPE1_LEN = 4;
	public static final int EMOICO_TYPE23_LEN = 3;

	public static final String RES_KEY_EXT = "obb";

	public static int RES_HEADER_SIZE = 50;

	static int mResInited = CHGlobal.AUTH_CODE_FAIL;

	static String mResPath = "";
	static String mResBuildDate = "";
	static String mResDescStr = "";

	protected static final Object mMutexRes = new Object();

	/**
	 * Directories scanned for {@code *.obb} CH350 resource packs (CH350 used {@code Global.APP_DIR_PATH}
	 * = /product/txDCS/ on OEM builds; this app adds app-private dirs too).
	 */
	private static final List<File> sEmojiResourceRoots = new CopyOnWriteArrayList<>();

	public static void clearEmojiResourceRoots() {
		sEmojiResourceRoots.clear();
	}

	public static void addEmojiResourceRoot(File directory) {
		if (directory != null && !sEmojiResourceRoots.contains(directory)) {
			sEmojiResourceRoots.add(directory);
		}
	}

	/** Replaces all roots with a single directory (legacy bootstrap API). */
	public static void setEmojiResourceDirectory(File directory) {
		clearEmojiResourceRoots();
		addEmojiResourceRoot(directory);
	}

	protected static List<String> getAllResFiles(File dirFile) {
		List<String> allResFiles = new ArrayList<>();
		try {
			File[] files = dirFile.listFiles();
			if (files != null) {
				for (int i = 0; i < files.length; i++) {
					File file = files[i];
					String filename = file.getName();
					if (filename.endsWith(RES_KEY_EXT)) {
						allResFiles.add(file.getAbsolutePath());
					}
				}
			}
		}
		catch (Exception e) {
			CHLog.printLog(CHLog.TAG_ALWAYS, TAG + "getAllResFiles caught:" + e.toString());
		}
		return allResFiles;
	}

	public static List<String> getSDCardList() {
		List<String> sdCardList = new ArrayList<>();
		File file = new File("/storage/");
		if (file.exists()) {
			File[] list = file.listFiles();
			if (list != null) {
				for (int i = 0; i < list.length; i++) {
					sdCardList.add(list[i].getAbsolutePath());
				}
			}
		}
		return sdCardList;
	}

	public static ArrayList<String> getResPath() {
		ArrayList<String> allResFiles = new ArrayList<>();
		for (File dirFile : sEmojiResourceRoots) {
			if (dirFile != null && dirFile.exists()) {
				allResFiles.addAll(getAllResFiles(dirFile));
			}
		}
		return allResFiles;
	}

	public static String getLastResPath() {
		String finalPath = "";
		String path = "";
		int initYear = -1;
		int initMon = -1;
		int initDay = -1;
		int initHour = -1;
		int initMin = -1;

		ArrayList<String> paths = getResPath();
		InputStream ios = null;

		for (int i = 0; i < paths.size(); i++) {
			int readSize = 0;
			path = paths.get(i);
			File file = new File(path);
			byte[] buffer = new byte[RES_HEADER_SIZE];
			try {
				ios = new FileInputStream(file);
			}
			catch (FileNotFoundException e) {
				e.printStackTrace();
			}
			if (ios == null) {
				continue;
			}

			try {
				ios.read(buffer, 0, RES_HEADER_SIZE);

				byte[] tmpBytes = new byte[RES_FILE_MARKER_SIZE];
				System.arraycopy(buffer, readSize, tmpBytes, 0, RES_FILE_MARKER_SIZE);
				readSize += RES_FILE_MARKER_SIZE;
				String keyStr = new String(tmpBytes);
				if (keyStr.compareTo(ResUtilsApi.RES_FILE_MARKER) != 0) {
					continue;
				}

				byte[] yearBytes = new byte[1];
				System.arraycopy(buffer, readSize, yearBytes, 0, 1);
				int year = CHGlobal.byteArrayToInt8(yearBytes, 0);
				readSize++;
				if (year < initYear) {
					continue;
				}
				initYear = year;

				byte[] monBytes = new byte[1];
				System.arraycopy(buffer, readSize, monBytes, 0, 1);
				int mon = CHGlobal.byteArrayToInt8(monBytes, 0);
				readSize++;
				if (mon < initMon) {
					continue;
				}
				initMon = mon;

				byte[] dayBytes = new byte[1];
				System.arraycopy(buffer, readSize, dayBytes, 0, 1);
				int day = CHGlobal.byteArrayToInt8(dayBytes, 0);
				readSize++;
				if (day < initDay) {
					continue;
				}
				initDay = day;

				byte[] hourBytes = new byte[1];
				System.arraycopy(buffer, readSize, hourBytes, 0, 1);
				int hour = CHGlobal.byteArrayToInt8(hourBytes, 0);
				readSize++;
				if (hour < initHour) {
					continue;
				}
				initHour = hour;

				byte[] minBytes = new byte[1];
				System.arraycopy(buffer, readSize, minBytes, 0, 1);
				int min = CHGlobal.byteArrayToInt8(minBytes, 0);
				readSize++;
				if (min < initMin) {
					continue;
				}
				initMin = min;
				finalPath = path;
			}
			catch (IOException e) {
				CHLog.printLog(CHLog.TAG_ALWAYS, TAG + "getLastResPath caught:" + e.toString());
			}
		}

		if (initYear > -1 && initMon > -1 && initDay > -1 && initHour > -1 && initMin > -1) {
			mResBuildDate = "자료기지갱신: 20" + initYear + "년 " + initMon + "월 " + initDay + "일";
		}
		return finalPath;
	}

	public static byte[] selectCode(byte[] codeBytes) {
		byte[] selAddr = new byte[ResUtilsApi.RES_ADDR_SIZE];

		if (codeBytes == null) {
			return null;
		}
		if (!ResUtilsApi.isNativeLibraryLoaded()) {
			return null;
		}
		try {
			int select = ResUtilsApi.select(codeBytes, selAddr);
			if (select > 0) {
				return selAddr;
			}
			else if (select == 0) {
				codeBytes = ResUtilsApi.EMO_NEW_CODE;
				select = ResUtilsApi.select(codeBytes, selAddr);
				if (select > 0) {
					return selAddr;
				}
				else {
					return null;
				}
			}
			else if (select == -1) {
				codeBytes = ResUtilsApi.EMO_OLD_CODE;
				select = ResUtilsApi.select(codeBytes, selAddr);
				if (select > 0) {
					return selAddr;
				}
				else {
					return null;
				}
			}
		}
		catch (Exception ex) {
			CHLog.printLog(CHLog.TAG_ALWAYS, TAG + "selectCode caught:" + ex.toString());
		}
		return null;
	}

	public static byte[] getParentCode(byte[] childCode) {
		boolean isSuccess = false;
		byte[] selAddr = selectCode(childCode);
		if (selAddr == null) {
			return null;
		}

		byte[] parentCodeStr = new byte[ResUtilsApi.RES_CODE_MAX_BYTES];
		int[] parentCodeSize = new int[1];
		isSuccess = ResUtilsApi.getParent(selAddr, parentCodeStr, parentCodeSize);
		if (!isSuccess) {
			return null;
		}

		byte[] parentCode = new byte[parentCodeSize[0]];
		System.arraycopy(parentCodeStr, 0, parentCode, 0, parentCodeSize[0]);

		return parentCode;
	}

	public static Bitmap getThumbBitmapFromNative(byte[] code) {
		if (code == null) {
			return null;
		}

		boolean isSuccess = false;
		byte[] dataAddress = new byte[ResUtilsApi.RES_ADDR_SIZE];
		byte[] selAddress = selectCode(code);
		if (selAddress == null) {
			return null;
		}

		int size = ResUtilsApi.getThumbBytes(selAddress, dataAddress);
		Bitmap bitmap = null;

		try {
			if (size > 0) {
				byte[] thumbData = new byte[size];
				isSuccess = ResUtilsApi.getThumbContent(dataAddress, thumbData);

				if (isSuccess) {
					bitmap = BitmapFactory.decodeByteArray(thumbData, 0, size);
				}
			}
		}
		catch (Exception ex) {
			CHLog.printLog(CHLog.TAG_ALWAYS, TAG + "getThumbBitmapFromNative caught:" + ex.toString());
		}
		return bitmap;
	}

	public static Bitmap getBitmapFromNative(byte[] code) {
		byte[] dataAddress = new byte[ResUtilsApi.RES_ADDR_SIZE];

		boolean isSuccess = false;
		byte[] selectCode = selectCode(code);
		if (selectCode == null) {
			return null;
		}

		int size = ResUtilsApi.getResourceBytes(selectCode, dataAddress);
		Bitmap bitmap = null;
		try {
			if (size > 0) {
				byte[] contentData = new byte[size];
				isSuccess = ResUtilsApi.getResourceContent(dataAddress, contentData);

				if (isSuccess) {
					bitmap = BitmapFactory.decodeByteArray(contentData, 0, size);
				}
			}
		}
		catch (Exception ex) {
			CHLog.printLog(CHLog.TAG_ALWAYS, TAG + "getBitmapFromNative caught:" + ex.toString());
		}
		return bitmap;
	}

	public static EmoModel getResourceInfo(byte[] emoBytes, int[] readPosParam) {
		int readPos = readPosParam[0];

		byte type = emoBytes[readPos];
		readPos++;

		int codeSize = CHGlobal.byteArrayToInt32(emoBytes, readPos);
		readPos += 4;

		byte[] codeBuffer = Arrays.copyOfRange(emoBytes, readPos, readPos + codeSize);
		readPos += codeSize;

		int textSize = CHGlobal.byteArrayToInt32(emoBytes, readPos);
		readPos += 4;

		byte[] textBuffer = Arrays.copyOfRange(emoBytes, readPos, readPos + textSize);
		readPos += textSize;

		int val1 = CHGlobal.byteArrayToInt16(emoBytes, readPos);
		readPos += 2;
		int val2 = CHGlobal.byteArrayToInt16(emoBytes, readPos);
		readPos += 2;
		int val3 = CHGlobal.byteArrayToInt16(emoBytes, readPos);
		readPos += 2;
		int val4 = CHGlobal.byteArrayToInt16(emoBytes, readPos);
		readPos += 2;
		byte permission = emoBytes[readPos];
		readPos++;

		EmoModel emo = new EmoModel();
		emo.mType = type;
		emo.mCodeBuffer = codeBuffer;
		emo.mTextBuffer = new String(textBuffer);
		emo.mPermission = permission;
		emo.mVal1 = val1;
		emo.mVal2 = val2;
		emo.mVal3 = val3;
		emo.mFlags = val4;

		readPosParam[0] = readPos;
		return emo;
	}

	public static ArrayList<EmoModel> getChildResourceInfo(byte[] resource, int limit) {
		ArrayList<EmoModel> emoList = new ArrayList<>();

		boolean isSuccess = false;
		byte[] selAddr = selectCode(resource);

		if (selAddr != null) {
			int byteSize = ResUtilsApi.getChildInfoBytes(selAddr);
			if (byteSize > 0) {
				byte[] emoBytes = new byte[byteSize];
				isSuccess = ResUtilsApi.getChildInfoContent(selAddr, emoBytes);
				if (isSuccess) {
					int cnt = CHGlobal.byteArrayToInt32(emoBytes, 0);
					int[] readPos = new int[1];
					readPos[0] = 4;

					for (int i = 0; i < cnt; i++) {
						EmoModel emo = getResourceInfo(emoBytes, readPos);
						if (CHGlobal.bitCheck(emo.mFlags, ResUtilsApi.EMO_RES_FLAG_CANT_SYSTEM)) {
							continue;
						}
						emo.mPosition = i;

						if (limit > 0) {
							emoList.add(emo);
							if (emoList.size() >= limit) {
								break;
							}
						}
						else {
							emoList.add(emo);
						}
					}
				}
			}
		}

		return emoList;
	}

	public static EmoModel getChildResource(byte[] resource, int index) {
		byte[] childAddr = new byte[ResUtilsApi.RES_ADDR_SIZE];
		int retCode = ResUtilsApi.selectChild(resource, index, ResUtilsApi.EMO_RES_FLAG_CANT_SYSTEM, childAddr);
		if (retCode <= 0)  {
			return null;
		}

		int infoBytes = ResUtilsApi.getInfoBytes(childAddr);
		if (infoBytes <= 0) {
			return null;
		}

		int[] readPos = new int[1];
		readPos[0] = 0;

		byte[] emoBytes = new byte[infoBytes];
		if (ResUtilsApi.getInfoContent(childAddr, emoBytes)) {
			EmoModel emo = getResourceInfo(emoBytes, readPos);
			emo.mPosition = -1;
			return emo;
		}
		return null;
	}

	public static int getChildCount(byte[] codeBytes) {
		if (!ResUtilsApi.isNativeLibraryLoaded()) {
			return 0;
		}
		byte[] selAddress = new byte[ResUtilsApi.RES_ADDR_SIZE];
		if (ResUtilsApi.select(codeBytes, selAddress) <= 0) {
			return 0;
		}

		return ResUtilsApi.getChildCount(selAddress);
	}

	public static EmoModel getInfoContent(byte[] codeBytes) {
		byte[] selAddr = selectCode(codeBytes);
		if (selAddr != null) {
			int infoBytes = ResUtilsApi.getInfoBytes(selAddr);
			if (infoBytes <= 0) {
				return null;
			}

			int[] readPos = new int[1];
			readPos[0] = 0;

			byte[] emoBytes = new byte[infoBytes];
			if (ResUtilsApi.getInfoContent(selAddr, emoBytes)) {
				EmoModel emo = getResourceInfo(emoBytes, readPos);
				emo.mPosition = -1;
				return emo;
			}
		}
		return null;
	}

	public static byte[] getResourceContent(byte[] bytes) {
		byte[] dataAddr = new byte[ResUtilsApi.RES_ADDR_SIZE];
		boolean isSuccess = false;
		byte[] selAddr = selectCode(bytes);
		if (selAddr != null) {
			int emoSize = ResUtilsApi.getResourceBytes(selAddr, dataAddr);
			if (emoSize > 0) {
				byte[] dataBytes = new byte[emoSize];
				isSuccess = ResUtilsApi.getResourceContent(dataAddr, dataBytes);

				if (isSuccess) {
					return dataBytes;
				}
			}
		}
		return null;
	}

	public static EmoModel getResourceImage() {
		try {
			ArrayList<EmoModel> rootList = getChildResourceInfo(ResUtilsApi.RES_SUBROOT_IMAGE.getBytes(), 1);
			byte[] codeBuffer = rootList.get(0).mCodeBuffer;

			ArrayList<EmoModel> subList = ResManager.getChildResourceInfo(codeBuffer, 1);
			if (subList.size() <= 0) {
				return null;
			}

			ArrayList<EmoModel> imgList = ResManager.getChildResourceInfo(subList.get(0).mCodeBuffer, 1);
			if (imgList.size() <= 0) {
				return null;
			}
			return imgList.get(0);
		}
		catch (Exception ex) {
			CHLog.printLog(CHLog.TAG_ALWAYS, TAG + "getResourceImage caught:" + ex.toString());
		}
		return null;
	}

	public static String getProductMarkStr(int productId) {
		String markStr = "";
		switch (productId) {
			case EMO_PRODUCT_JINDALRAE: {
				markStr = "[진달래]";
				break;
			}
			case EMO_PRODUCT_SINGI: {
				markStr = "[신기]";
				break;
			}
			case EMO_PRODUCT_BOMDONGSAN: {
				markStr = "[봄동산]";
				break;
			}
			case EMO_PRODUCT_DOVE: {
				markStr = "[비둘기]";
				break;
			}
			case EMO_PRODUCT_JONSUNG: {
				markStr = "[전승]";
				break;
			}
			case EMO_PRODUCT_SAMHUNG: {
				markStr = "[삼흥]";
				break;
			}
			case EMO_PRODUCT_SNOW: {
				markStr = "[눈사람]";
				break;
			}
			default: {
				markStr = "[그림기호]";
				break;
			}
		}
		return markStr;
	}

	public static int initResourceDb() {
		synchronized (mMutexRes) {
			if (mResInited == CHGlobal.AUTH_CODE_SUCCESS) {
				return mResInited;
			}
			releaseResourceDb();

			mResDescStr = "";
			mResInited = CHGlobal.AUTH_CODE_FAIL;

			if (!ResUtilsApi.ensureNativeLibraryLoaded()) {
				mResInited = CHGlobal.AUTH_CODE_INVALID_EXTERNAL;
				return mResInited;
			}

			mResPath = ResManager.getLastResPath();

			if (mResPath == null || mResPath.length() < 1) {
				mResInited = CHGlobal.AUTH_CODE_INVALID_FILE;
				return mResInited;
			}

			ResUtilsApi.initParser();

			byte[] descBuffer = new byte[ResUtilsApi.RES_FILE_DESC_MAX_SIZE];
			int[] descSize = new int[1];
			mResInited = ResUtilsApi.initInstance(mResPath, CHGlobal.CH_PRODUCT_ID, descBuffer, descSize);

			if (mResInited == CHGlobal.AUTH_CODE_SUCCESS) {
				byte[] realBytes = new byte[descSize[0]];
				System.arraycopy(descBuffer, 0, realBytes, 0, descSize[0]);
				mResDescStr = new String(realBytes, StandardCharsets.UTF_8);

				CHGlobal.addStatus(CHGlobal.STATUS_RES_INITED);
			}
		}
		return mResInited;
	}

	public static void releaseResourceDb() {
		synchronized (mMutexRes) {
			if (mResInited == CHGlobal.AUTH_CODE_SUCCESS && ResUtilsApi.isNativeLibraryLoaded()) {
				ResUtilsApi.exitParser();
				ResUtilsApi.exitInstance();
			}

			mResPath = "";
			mResBuildDate = "";
			mResDescStr = "";
			mResInited = CHGlobal.AUTH_CODE_FAIL;
			CHGlobal.removeStatus(CHGlobal.STATUS_RES_INITED);
		}
	}

	public static HashMap<String, Integer> getSystemEmoCodes() {
		HashMap<String, Integer> retVal = new HashMap<>();
		if (!ResUtilsApi.isNativeLibraryLoaded()) {
			return retVal;
		}

		int byteSize = 0;
		byte[] selAddr = new byte[ResUtilsApi.RES_ADDR_SIZE];
		ResUtilsApi.select(ResUtilsApi.RES_SUBROOT_EMOICO.getBytes(), selAddr);
		byteSize = ResUtilsApi.getChildInfoRecursivelyBytes(selAddr, ResUtilsApi.RES_TYPE_EMOICO);
		if (byteSize < 0) {
			return retVal;
		}

		byte[] childBuffer = new byte[byteSize];
		boolean isSuccess = ResUtilsApi.getChildInfoRecursivelyContent(selAddr, ResUtilsApi.RES_TYPE_EMOICO, childBuffer);
		if (!isSuccess) {
			return retVal;
		}

		int cnt = CHGlobal.byteArrayToInt32(childBuffer, 0);
		int readSize = 4;

		for (int i = 0; i < cnt; i++) {
			byte type = childBuffer[readSize];
			readSize++;

			int codeSize = CHGlobal.byteArrayToInt32(childBuffer, readSize);
			readSize += 4;
			byte[] codeBuffer = new byte[codeSize];
			System.arraycopy(childBuffer, readSize, codeBuffer, 0, codeSize);
			readSize += codeSize;

			String codeStr = new String(codeBuffer);
			retVal.put(codeStr, 1);

			int len = codeStr.length();
			byte[] tmpBuffer = codeStr.getBytes();

			int textSize = CHGlobal.byteArrayToInt32(childBuffer, readSize);
			readSize += 4;

			byte[] textBuffer = new byte[textSize];
			System.arraycopy(childBuffer, readSize, textBuffer, 0, textSize);
			readSize += textSize;

			int val1 = CHGlobal.byteArrayToInt16(childBuffer, readSize);
			readSize += 2;
			int val2 = CHGlobal.byteArrayToInt16(childBuffer, readSize);
			readSize += 2;

			byte permission = childBuffer[readSize];
			readSize++;
		}

		return retVal;
	}

	public static String buildEmoDispStr(EmoModel emo, int charIndex) {
		return buildCodeDispStr(emo.mCodeBuffer, charIndex);
	}

	public static String buildCodeDispStr(byte[] codeBuffer, int charIndex) {
		String dispStr = "";

		if (codeBuffer[0] == (byte) BYTE_EMOICO_START_1 ||
				codeBuffer[0] == (byte) BYTE_EMOICO_START_2 ||
				codeBuffer[0] == (byte) BYTE_EMOICO_START_3) {
			dispStr = new String(codeBuffer);
		}
		else {
			if (!ResUtilsApi.isNativeLibraryLoaded()) {
				return "";
			}
			byte[] dispBytes = new byte[ResUtilsApi.RES_EMOGI_TEXT_SIZE];
			boolean isSuccess = ResUtilsApi.buildEmogi(codeBuffer, charIndex, dispBytes);
			if (isSuccess) {
				dispStr = new String(dispBytes);
			}
		}
		return dispStr;
	}

	public static EmoModel getEmo(String emoStr) {
		byte[] emoCodeBytes = emoStr.getBytes();
		EmoModel emo = getPossibleEmo(emoCodeBytes, 0, emoCodeBytes.length, false);
		if (emo == null) {
			return null;
		}
		if (emo.mVal1 != EMO_PRODUCT_CH) {
			return null;
		}
		return emo;
	}

	public static EmoModel getPossibleEmo(byte[] buffer, int pos, int len, boolean deepCheck) {
		EmoModel emo = null;
		if (!ResUtilsApi.isNativeLibraryLoaded()) {
			return null;
		}

		//emo.mType:리쏘스타입
		//emo.mVal1:제작단위
		//emo.mVal2:바이트길이
		//emo.mVal3:문자시작인덱스
		//emo.mFlags:문자길이
		//emo.mScaleWidth: 화상의 너비
		//emo.mScaleHeight: 화상의 높이

		byte tmp = buffer[pos];
		if (tmp == (byte)BYTE_EMOICO_START_1) {
			emo = new EmoModel();
			emo.mType = RES_TYPE_EMOICO;
			emo.mVal1 = EMO_PRODUCT_CH;
			emo.mVal2 = EMOICO_TYPE1_LEN;
			emo.mCodeBuffer = new byte[EMOICO_TYPE1_LEN];
			System.arraycopy(buffer, pos, emo.mCodeBuffer, 0, EMOICO_TYPE1_LEN);
			emo.mTextBuffer = new String(emo.mCodeBuffer);

			emo.mVal3 = 0;
			if (pos > 0) {
				emo.mVal3 = (new String(buffer, 0, pos)).length();
			}
			emo.mFlags = emo.mTextBuffer.length();

			//검사
			byte[] selAddr = new byte[ResUtilsApi.RES_ADDR_SIZE];
			int selectResult = ResUtilsApi.select(emo.mCodeBuffer, selAddr);
			if (selectResult <= 0) {
				return null;
			}
		}
		else if (tmp == (byte)BYTE_EMOICO_START_2 || tmp == (byte)BYTE_EMOICO_START_3) {
			emo = new EmoModel();
			emo.mType = RES_TYPE_EMOICO;
			emo.mVal1 = EMO_PRODUCT_CH;
			emo.mVal2 = EMOICO_TYPE23_LEN;
			emo.mCodeBuffer = new byte[EMOICO_TYPE23_LEN];
			System.arraycopy(buffer, pos, emo.mCodeBuffer, 0, EMOICO_TYPE23_LEN);
			emo.mTextBuffer = new String(emo.mCodeBuffer);

			emo.mVal3 = 0;
			if (pos > 0) {
				emo.mVal3 = (new String(buffer, 0, pos)).length();
			}
			emo.mFlags = emo.mTextBuffer.length();

			//검사
			byte[] selAddr = new byte[ResUtilsApi.RES_ADDR_SIZE];
			int selectResult = ResUtilsApi.select(emo.mCodeBuffer, selAddr);
			if (selectResult <= 0) {
				return null;
			}
		}
		else if (tmp == (byte)BYTE_EMO_HEADER_START && len - pos >= EMO_HEADER_SIZE) {
			//이모지인 경우 영문검사
			for (int i = 1; i< EMO_HEADER_SIZE; i++) {
				if (!CHGlobal.isAscii(buffer[pos + i])) {
					return null;
				}
			}

			//이모지헤더검사
			byte[] tmpBuffer = new byte[EMO_HEADER_SIZE];
			System.arraycopy(buffer, pos, tmpBuffer, 0, EMO_HEADER_SIZE);

			int[] emoMode = new int[2];
			int productId = ResUtilsApi.parseHeader(tmpBuffer, emoMode);
			if (productId <= EMO_PRODUCT_NONE ) {
				return null;
			}

			//EmoModel얻기
			int emoByteSize = emoMode[1];
			emo = new EmoModel();
			emo.mType = RES_TYPE_NONE;
			emo.mCodeBuffer = new byte[RES_EMOGI_BYTE_SIZE];
			emo.mVal1 = productId;
			emo.mVal2 = EMO_HEADER_SIZE + emoByteSize;

			emo.mVal3 = 0;
			if (pos > 0) {
				emo.mVal3 = (new String(buffer, 0, pos)).length();
			}
			emo.mFlags = emo.mVal2;

			if (productId == EMO_PRODUCT_CH && emoMode[0] == EMO_TYPE_EMOGI) {
				emo.mType = RES_TYPE_EMOGI;
				emo.mTextBuffer = new String(buffer, pos, EMO_HEADER_SIZE + emoByteSize);

				tmpBuffer = new byte[emoByteSize];
				System.arraycopy(buffer, pos + EMO_HEADER_SIZE, tmpBuffer, 0, emoByteSize);

				boolean isSuccess = ResUtilsApi.parseEmogi(tmpBuffer, emo.mVal3, emo.mCodeBuffer);
				if (!isSuccess) {
					return null;
				}

				//검사
				byte[] selAddr = new byte[ResUtilsApi.RES_ADDR_SIZE];
				int selectResult = ResUtilsApi.select(emo.mCodeBuffer, selAddr);
				if (selectResult > 0) {
					if (deepCheck) {
						int infoBytes = ResUtilsApi.getInfoBytes(selAddr);
						if (infoBytes <= 0) {
							return null;
						}

						int[] readPos = new int[1];
						readPos[0] = 0;

						byte[] emoBytes = new byte[infoBytes];
						if (!ResUtilsApi.getInfoContent(selAddr, emoBytes)) {
							return null;
						}

						EmoModel tmpEmo = getResourceInfo(emoBytes, readPos);
						emo.mType = tmpEmo.mType;
					}
				}
				else if (selectResult == 0) {
					emo.mCodeBuffer = ResUtilsApi.EMO_NEW_CODE;
				}
				else {
					emo.mCodeBuffer = ResUtilsApi.EMO_OLD_CODE;
				}
			}
		}
		return emo;
	}

	public static int getMmsBytes(byte[] buffer, int len) {
		try {
			if (!ResUtilsApi.isNativeLibraryLoaded()) {
				return -1;
			}
			if (buffer[0] == (byte)BYTE_EMO_HEADER_START && len > EMO_HEADER_SIZE) {
				//이모지인 경우 영문검사
				for (int i = 1; i < EMO_HEADER_SIZE; i++) {
					if (!CHGlobal.isAscii(buffer[i])) {
						return -1;
					}
				}

				//이모지헤더검사
				int[] emoMode = new int[2];
				byte[] emoBuffer = new byte[EMO_HEADER_SIZE];
				System.arraycopy(buffer,0, emoBuffer,0, EMO_HEADER_SIZE);

				int productId = ResUtilsApi.parseHeader(emoBuffer, emoMode);
				if (productId == EMO_PRODUCT_CH && emoMode[0] == EMO_TYPE_MMS) {
					return EMO_HEADER_SIZE + emoMode[1];
				}
			}
		}
		catch (Exception ex) {
			CHLog.printLog(CHLog.TAG_ALWAYS, TAG + " isMmsBytes caught: " + ex.toString());
		}
		return -1;
	}

	public static int getResInitCode() {
		return mResInited;
	}

	public static String getResDescStr() {
		return mResDescStr;
	}

	public static String getResBuildDate() {
		return mResBuildDate;
	}
}