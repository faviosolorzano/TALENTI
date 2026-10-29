package com.talenti.exam;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import javax.imageio.ImageIO;

/** Imágenes de prueba generadas en el momento (ninguna foto real de personas). */
public final class TestJpegs {
	private TestJpegs() { }
	public static BufferedImage pattern(int w,int h) {
		var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
		for(int y=0;y<h;y++) for(int x=0;x<w;x++) image.setRGB(x,y,((x*3)&0xFF)<<16|((y*5)&0xFF)<<8|((x+y)&0xFF));
		return image;
	}
	public static byte[] write(BufferedImage image,String format) {
		try(var out=new ByteArrayOutputStream()) {
			if(!ImageIO.write(image,format,out)) throw new IllegalStateException(format);
			return out.toByteArray();
		}
		catch(IOException e) { throw new IllegalStateException(e); }
	}
	public static byte[] jpeg(int w,int h) { return write(pattern(w,h),"jpg"); }
	public static byte[] png(int w,int h) { return write(pattern(w,h),"png"); }
	/** JPEG con un segmento EXIF (APP1) que lleva {@code mark}: simula ubicación u otros metadatos. */
	public static byte[] withExif(byte[] jpeg,String mark) {
		byte[] payload=("Exif\0\0GPS "+mark).getBytes(StandardCharsets.ISO_8859_1);
		int length=payload.length+2;
		byte[] out=new byte[jpeg.length+4+payload.length];
		out[0]=(byte)0xFF; out[1]=(byte)0xD8; out[2]=(byte)0xFF; out[3]=(byte)0xE1;
		out[4]=(byte)(length>>8); out[5]=(byte)length;
		System.arraycopy(payload,0,out,6,payload.length);
		System.arraycopy(jpeg,2,out,6+payload.length,jpeg.length-2);
		return out;
	}
	/** Bytes que empiezan como JPEG pero no se pueden decodificar. */
	public static byte[] fakeJpeg(int size) {
		byte[] b=new byte[size]; new Random(7).nextBytes(b);
		b[0]=(byte)0xFF; b[1]=(byte)0xD8; b[2]=(byte)0xFF; b[3]=(byte)0xE0;
		return b;
	}
	public static boolean hasSegment(byte[] jpeg,int marker) {
		for(int i=0;i+1<jpeg.length;i++) if((jpeg[i]&0xFF)==0xFF&&(jpeg[i+1]&0xFF)==marker) return true;
		return false;
	}
	public static boolean contains(byte[] haystack,String needle) {
		return new String(haystack,StandardCharsets.ISO_8859_1).contains(needle);
	}
}
