package com.talenti.exam.snapshot;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.imageio.*;
import javax.imageio.event.IIOReadWarningListener;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/**
 * Validación y limpieza de una foto (014-54): tamaño en bytes, JPEG por sus bytes iniciales (no por el nombre ni el tipo
 * declarado), medidas leídas de la cabecera ANTES de decodificar (evita imágenes enormes en píxeles) y decodificación
 * completa sin advertencias. Después se vuelve a codificar desde los píxeles: el resultado no lleva EXIF, ubicación,
 * comentarios ni miniaturas, solo la cabecera JFIF del codificador.
 */
public final class SnapshotImages {
	static { ImageIO.setUseCache(false); }
	private SnapshotImages() { }
	public enum Problem { EMPTY, TOO_LARGE, NOT_JPEG, UNREADABLE, DIMENSIONS }
	public static final class Rejected extends Exception {
		private final Problem problem;
		Rejected(Problem problem) { super(null,null,false,false); this.problem=problem; }
		public Problem problem() { return problem; }
	}
	public record Clean(byte[] jpeg,int width,int height,String sha256) {
		@Override public String toString() { return "SnapshotImage["+width+"x"+height+", "+jpeg.length+" bytes]"; }
	}
	private static final float[] QUALITIES={0.8f,0.7f,0.6f,0.5f,0.4f,0.3f,0.2f};

	public static Clean clean(byte[] bytes,long maxBytes,int maxSide) throws Rejected {
		if(bytes==null||bytes.length==0) throw new Rejected(Problem.EMPTY);
		if(bytes.length>maxBytes) throw new Rejected(Problem.TOO_LARGE);
		if(bytes.length<4||(bytes[0]&0xFF)!=0xFF||(bytes[1]&0xFF)!=0xD8||(bytes[2]&0xFF)!=0xFF) throw new Rejected(Problem.NOT_JPEG);
		BufferedImage decoded=decode(bytes,maxSide);
		int w=decoded.getWidth(), h=decoded.getHeight();
		BufferedImage rgb=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
		// Copia de píxeles sin Graphics2D: solo color, nada de lo que traía el archivo.
		rgb.setRGB(0,0,w,h,decoded.getRGB(0,0,w,h,null,0,w),0,w);
		for(float q:QUALITIES) {
			byte[] out=encode(rgb,q);
			if(out.length<=maxBytes) return new Clean(out,w,h,sha256(out));
		}
		throw new Rejected(Problem.TOO_LARGE);
	}
	private static BufferedImage decode(byte[] bytes,int maxSide) throws Rejected {
		var readers=ImageIO.getImageReadersByFormatName("jpeg");
		if(!readers.hasNext()) throw new IllegalStateException("Sin lector JPEG");
		ImageReader reader=readers.next();
		boolean[] warned={false};
		try(ImageInputStream in=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			reader.setInput(in,true,true);
			reader.addIIOReadWarningListener((IIOReadWarningListener)(source,warning)->warned[0]=true);
			int w=reader.getWidth(0), h=reader.getHeight(0);
			if(w<1||h<1) throw new Rejected(Problem.UNREADABLE);
			if(w>maxSide||h>maxSide) throw new Rejected(Problem.DIMENSIONS);
			BufferedImage image=reader.read(0);
			if(image==null||warned[0]||image.getWidth()!=w||image.getHeight()!=h) throw new Rejected(Problem.UNREADABLE);
			return image;
		}
		catch(IOException|RuntimeException e) { throw new Rejected(Problem.UNREADABLE); }
		finally { reader.dispose(); }
	}
	private static byte[] encode(BufferedImage image,float quality) {
		ImageWriter writer=ImageIO.getImageWritersByFormatName("jpeg").next();
		try(var buffer=new ByteArrayOutputStream(); ImageOutputStream out=ImageIO.createImageOutputStream(buffer)) {
			writer.setOutput(out);
			ImageWriteParam param=writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);
			writer.write(null,new IIOImage(image,null,null),param);
			out.flush();
			return buffer.toByteArray();
		}
		catch(IOException e) { throw new IllegalStateException("No se pudo codificar la foto",e); }
		finally { writer.dispose(); }
	}
	private static String sha256(byte[] bytes) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
		catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
	}
}
