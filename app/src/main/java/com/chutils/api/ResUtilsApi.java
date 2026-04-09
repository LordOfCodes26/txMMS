package com.chutils.api;

public class ResUtilsApi {
    public static final String RES_SUBROOT_EMOICO = "emoico";
    public static final String RES_SUBROOT_EMOGI = "emogi";
    public static final String RES_SUBROOT_EMOTXT = "emotext";
    public static final String RES_SUBROOT_CALLUI = "callui";
    public static final String RES_SUBROOT_IMAGE = "image";
    public static final String RES_SUBROOT_SOUND = "sound";
    public static final String RES_SUBROOT_TEXT = "text";
    public static final String RES_SUBROOT_FONT = "font";
    public static final String RES_SUBROOT_EFFECT = "effect";
    public static final String RES_SUBROOT_DATA = "data";
    public static final String RES_SUBROOT_SYSTEM = "system";

    public static final int RES_TYPE_NONE = 0;
    public static final int RES_TYPE_GROUP = 1;
    public static final int RES_TYPE_IMAGE = 2;
    public static final int RES_TYPE_TEXT = 3;
    public static final int RES_TYPE_SOUND = 4;
    public static final int RES_TYPE_EFFECT = 5;
    public static final int RES_TYPE_FONT = 6;
    public static final int RES_TYPE_MOVIE = 7;

    public static final int RES_TYPE_EMOICO = 100;
    public static final int RES_TYPE_EMOGI = 101;
    public static final int RES_TYPE_EMOANI = 102;

    public static final int RES_CODE_MAX_BYTES = 255;
    public static final int RES_ADDR_SIZE = 8;

    public static final int RES_EMOGI_BYTE_SIZE = 2;
    public static final int RES_EMOGI_TEXT_SIZE = 8;

    public static final int RES_MMS_BYTE_SIZE = 31;
    public static final int RES_MMS_TEXT_SIZE = 30;

    //RES_TYPE_EMOANI의 애니방식
    public static final int RES_EMOANI_NONE = 0;
    public static final int RES_EMOANI_SELECT = 1;
    public static final int RES_EMOANI_GROUP = 2;
    public static final int RES_EMOANI_LONG = 3;
    public static final int RES_EMOANI_FIRST = 4;

    //그림기호관련프로그람을 작성하는 단위목록
    public static final int EMO_PRODUCT_NONE = 0;
    public static final int EMO_PRODUCT_JINDALRAE = 1;
    public static final int EMO_PRODUCT_SINGI = 2;
    public static final int EMO_PRODUCT_BOMDONGSAN = 3;
    public static final int EMO_PRODUCT_DOVE = 4;
    public static final int EMO_PRODUCT_JONSUNG = 5;
    public static final int EMO_PRODUCT_ACK = 6;
    public static final int EMO_PRODUCT_SAMHUNG = 7;
    public static final int EMO_PRODUCT_SNOW = 8;

    public static final int EMO_PRODUCT_CH = 6;

    //이모지헤더관련
    public static final int EMO_TYPE_NONE = 0;
    public static final int EMO_TYPE_EMOGI = 1;
    public static final int EMO_TYPE_MMS = 2;

    public static final int EMO_HEADER_SIZE = 5;

    public static final byte[] EMO_NEW_CODE = {(byte)0xFF, (byte)0xFE};
    public static final byte[] EMO_OLD_CODE = {(byte)0xFF, (byte)0xFD};

    //리쏘스Flags
    public static final int EMO_RES_FLAG_ALL = 0x00;
    public static final int EMO_RES_FLAG_NEW = 0x01;
    public static final int EMO_RES_FLAG_MMS = 0x02;
    public static final int EMO_RES_FLAG_CANT_SYSTEM = 0x04;

    //Res파일관련
    public static final String RES_FILE_MARKER = "CHRS1";
    public static final int RES_FILE_MARKER_SIZE = 5;
    public static final int RES_FILE_DTM_BYTES = 5;
    public static final int RES_FILE_DESC_MAX_SIZE = 1024;

    private static volatile Boolean sNativeOk = null;

    /**
     * Loads libresutils.so once. Call before any native method. Without the .so (see jniLibs/README),
     * returns false and all resource calls must no-op.
     */
    public static synchronized boolean ensureNativeLibraryLoaded() {
        if (sNativeOk != null) {
            return sNativeOk;
        }
        try {
            System.loadLibrary("resutils");
            sNativeOk = true;
        } catch (UnsatisfiedLinkError e) {
            sNativeOk = false;
        }
        return sNativeOk;
    }

    public static boolean isNativeLibraryLoaded() {
        if (sNativeOk == null) {
            ensureNativeLibraryLoaded();
        }
        return Boolean.TRUE.equals(sNativeOk);
    }

    public ResUtilsApi() {
    }

    public static native int            initInstance(String file_path, int product_id, byte[] desc_buffer, int[] desc_size);
    public static native int            exitInstance();

    public static native void           initParser();
    public static native void           exitParser();

    public static native int            select(byte[] code, byte[] sel_address);
    public static native int            selectChild(byte[] code, int child_index, int unused_bit, byte[] sel_address);

    public static native int            getChildCount(byte[] sel_address);
    public static native boolean        getParent(byte[] sel_address, byte[] code, int[] size);

    /*	ChildResource
            i32 child_count
            {
                u8			type
                i32+bytes:	code
                i32+bytes:	text
                i16			val1(w)
                i16			val2(h)
                i16			val3(fps)
                i16         val4(flags)
                u8  		permission
            }
    */
    public static native int            getChildInfoBytes(byte[] sel_address);
    public static native boolean        getChildInfoContent(byte[] sel_address, byte[] child_buffer);

    /*	ChildResource
            i32 child_count
            {
                u8			type
                i32+bytes:	code
                i32+bytes:	text
                i16			val1(w)
                i16			val2(h)
                i16			val3(fps)
                i16         val4(flags)
                u8  		permission
            }
    */
    public static native int            getChildInfoRecursivelyBytes(byte[] sel_address, int child_type);
    public static native boolean        getChildInfoRecursivelyContent(byte[] sel_address, int child_type, byte[] child_buffer);

    /*	ChildCode
        i32 child_count
        {
            i32+bytes:	code
        }
    */
    public static native int            getChildCodeBytes(byte[] sel_address);
    public static native boolean        getChildCodeContent(byte[] sel_address, byte[] child_buffer);

    /*	ResourceInfo
                u8			type
                i32+bytes:	code
                i32+bytes:	text
                i16			val1(w)
                i16			val2(h)
                i16			val3(fps)
                i16         val4(flags)
                u8  		permission
    */
    public static native int            getInfoBytes(byte[] sel_address);
    public static native boolean        getInfoContent(byte[] sel_address, byte[] info_buffer);

    /*	AllResourceInfo
        i32 all_count
        {
            ResourceInfo:	data
            i32+bytes:		thumb
        }
    */
    public static native int            getAllInfoThumbBytes();
    public static native boolean        getAllInfoThumbContent(byte[] all_info_buffer);

    /* ThumbData
            i32+bytes:		thumb
    */
    public static native int            getThumbBytes(byte[] sel_address, byte[] data_address);
    public static native boolean        getThumbContent(byte[] data_address, byte[] thumb_buffer);

    /*	Content
        -Emoico,Emogi: frame_count(i32), frame1_pos(i32), empty(i32), ... ,frame_end_pos(i32), frame1_content,...
        -Emoani: frame_count(i32), frame1_pos(i32), empty(i32), ... ,frame_end_pos(i32), x1(u16), y1(u16), frame1_content,...
        -Image: image_size(i32), image_content
    */
    public static native int            getResourceBytes(byte[] sel_address, byte[] data_address);
    public static native boolean        getResourceContent(byte[] data_address, byte[] res_buffer);

    /*  Frame Content
		-Emoico,Emogi: frame_size(i32), frame_content
		-Emoani: frame_size(i32), x(u16), y(u16), frame_content
	*/
	public static native int            getFrameCount(byte[] sel_address);
    public static native int            getFrameBytes(byte[] sel_address, int index, byte[] data_address);
    public static native boolean        getFrameContent(byte[] data_address, byte[] res_buffer);

    public static native boolean        getAllFrameBytes(byte[] sel_address, byte[] data_address);
    public static native int            getOneFrameBytes(byte[] data_address, int index);
    public static native boolean        getOneFrameContent(byte[] data_address, int index, byte[] res_buffer);

    public static native void           clearDataAddress(byte[] data_address);

    //Emogi생성및 해석부분
    public static native boolean        buildEmogi(byte[] buffer, int char_index, byte[] txt_buffer);
    public static native boolean        buildMMS(byte[] buffer, int char_count, byte[] txt_buffer, int[] txt_buffer_size);

    public static native int            parseHeader(byte[] txt_buffer, int[] emo_buffer);

    public static native boolean        parseEmogi(byte[] txt_buffer, int char_index, byte[] buffer);
    public static native boolean        parseMMS(byte[] txt_buffer, int char_count, byte[] buffer, int[] buffer_size);
}
