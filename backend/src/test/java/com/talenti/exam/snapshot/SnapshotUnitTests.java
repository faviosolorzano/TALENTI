package com.talenti.exam.snapshot;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.talenti.exam.TestJpegs;
import com.talenti.exam.snapshot.SnapshotImages.Problem;

/** 014-54: validación por bytes, medidas, recodificación sin metadatos y almacenamiento en disco. */
class SnapshotUnitTests {
	private static final long MAX=150*1024;
	private static Problem problem(byte[] bytes) {
		try { SnapshotImages.clean(bytes,MAX,640); return null; }
		catch(SnapshotImages.Rejected r) { return r.problem(); }
	}
	@Test void t014_54_rejectsByBytesNotByName() {
		assertThat(problem(TestJpegs.png(64,48))).isEqualTo(Problem.NOT_JPEG);
		assertThat(problem(new byte[0])).isEqualTo(Problem.EMPTY);
		assertThat(problem(TestJpegs.fakeJpeg((int)MAX+1))).isEqualTo(Problem.TOO_LARGE);
		assertThat(problem(TestJpegs.fakeJpeg(4000))).isEqualTo(Problem.UNREADABLE);
		byte[] good=TestJpegs.jpeg(320,240);
		assertThat(problem(java.util.Arrays.copyOf(good,good.length/2))).isEqualTo(Problem.UNREADABLE);
		assertThat(problem(TestJpegs.write(new java.awt.image.BufferedImage(641,100,java.awt.image.BufferedImage.TYPE_INT_RGB),"jpg"))).isEqualTo(Problem.DIMENSIONS);
		// Enorme en píxeles pero liviana en bytes: se rechaza por la cabecera, sin decodificarla.
		byte[] huge=TestJpegs.write(new java.awt.image.BufferedImage(2000,2000,java.awt.image.BufferedImage.TYPE_INT_RGB),"jpg");
		assertThat(huge.length).isLessThan((int)MAX);
		assertThat(problem(huge)).isEqualTo(Problem.DIMENSIONS);
	}
	@Test void t014_54_reencodesWithoutExifOrLocation() throws Exception {
		String mark="ubicacion"+UUID.randomUUID().toString().replace("-","");
		byte[] withExif=TestJpegs.withExif(TestJpegs.jpeg(640,480),mark);
		assertThat(TestJpegs.contains(withExif,mark)).isTrue();
		var clean=SnapshotImages.clean(withExif,MAX,640);
		assertThat(TestJpegs.contains(clean.jpeg(),mark)).isFalse();
		assertThat(TestJpegs.contains(clean.jpeg(),"Exif")).isFalse();
		assertThat(clean.width()).isEqualTo(640); assertThat(clean.height()).isEqualTo(480);
		assertThat(clean.jpeg().length).isLessThanOrEqualTo((int)MAX);
		assertThat(clean.sha256()).hasSize(64);
		assertThat(ImageIO.read(new java.io.ByteArrayInputStream(clean.jpeg())).getWidth()).isEqualTo(640);
		assertThat(clean.toString()).doesNotContain(mark);
	}
	@Test void t014_54_diskStorageUsesKeysWithoutNamesAndRejectsTraversal(@TempDir Path dir) throws Exception {
		var storage=new LocalSnapshotStorage(dir);
		UUID attempt=UUID.randomUUID(), snap=UUID.randomUUID();
		String key=ExamSnapshotStorage.key(attempt,snap);
		storage.put(key,new byte[]{1,2,3});
		assertThat(Files.exists(dir.resolve(attempt.toString()).resolve(snap+".jpg"))).isTrue();
		assertThat(storage.read(key)).contains(new byte[]{1,2,3});
		assertThat(storage.list()).extracting(ExamSnapshotStorage.Stored::key).containsExactly(key);
		storage.delete(key); storage.delete(key);
		assertThat(storage.read(key)).isEmpty();
		assertThat(Files.exists(dir.resolve(attempt.toString()))).isFalse();
		assertThatThrownBy(()->storage.put("../"+snap+"/x.jpg",new byte[]{1})).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(()->storage.read(attempt+"/ana-perez.jpg")).isInstanceOf(IllegalArgumentException.class);
	}
}
