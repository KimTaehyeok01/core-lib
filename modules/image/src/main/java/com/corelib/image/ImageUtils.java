package com.corelib.image;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.lang.StringUtils;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * 이미지 변환 — byte[] 를 받아 byte[] 를 돌려준다 (저장은 file 모듈·호출측).
 * <ul>
 *   <li>해상도 폭탄 방지: 디코딩 전에 헤더로 가로×세로를 확인해 core.image.max-pixels(기본 4천만) 초과면 거절</li>
 *   <li>EXIF 방향 보정: 휴대폰 세로 사진이 눕지 않게 먼저 바로 세운다</li>
 *   <li>목표 크기 기준 디코딩: 모든 규격이 원본의 절반 이하면 건너뛰어 읽어 메모리를 줄인다 (가장 큰 규격의 2배 해상도는 남긴다)</li>
 *   <li>ImageIO 전역 설정(useCache 등)은 바꾸지 않는다 — 입출력은 메모리 캐시 스트림을 직접 만든다 (서버 디스크에 임시 파일 없음)</li>
 *   <li>다시 인코딩하므로 EXIF(GPS 위치 등) 메타데이터는 결과에서 빠진다</li>
 *   <li>읽기: jpeg·png·gif(첫 프레임)·bmp·webp / 쓰기: jpeg·png</li>
 *   <li>확대는 하지 않는다 — 원본이 작으면 원본 크기 그대로</li>
 * </ul>
 */
public final class ImageUtils {

	private static final Set<String> READABLE = Set.of("jpeg", "png", "gif", "bmp", "webp");
	/** 건너뛰어 읽기(서브샘플링)를 확인한 JDK 기본 리더 — webp 플러그인은 원본 해상도로 읽는다 */
	private static final Set<String> SUBSAMPLING = Set.of("jpeg", "png", "gif", "bmp");
	private static volatile long maxPixels = 40_000_000L;

	private ImageUtils() {
	}

	/** 기동 시 {@link ImageInitialize} 가 core.image.max-pixels 를 넣는다 */
	static void initialize(long initializeMaxPixels) {
		maxPixels = initializeMaxPixels;
	}

	/** 이 런타임에서 실제로 읽을 수 있는 형식 — webp 플러그인이 빠지면 목록에서 사라진다 */
	public static List<String> readableFormats() {
		return READABLE.stream().filter(format -> ImageIO.getImageReadersByFormatName(format).hasNext()).sorted().toList();
	}

	/** 디코딩 없이 크기·형식 — 업로드 직후 검사용 */
	public static ImageDto.Info info(byte[] image) {
		try (ImageInputStream input = input(image)) {
			ImageReader reader = reader(input);
			try {
				return ImageDto.Info.builder().width(reader.getWidth(0)).height(reader.getHeight(0)).format(formatOf(reader)).build();
			} finally {
				reader.dispose();
			}
		} catch (IOException e) {
			throw new CoreException(ImageError.INVALID_IMAGE, ImageError.INVALID_IMAGE.message(), e);
		}
	}

	/** 상자(maxWidth×maxHeight) 안에 들어가게 비율 유지 축소 */
	public static ImageDto.Result resize(byte[] image, int maxWidth, int maxHeight, ImageDto.Format format) {
		return variants(image, List.of(ImageDto.Spec.builder().name("resize").width(maxWidth).height(maxHeight).mode(ImageDto.Mode.FIT).format(format).build()))
			.get(0);
	}

	/** 정확히 width×height — 넘치는 쪽을 가운데 기준으로 잘라낸다 (정사각 썸네일 등) */
	public static ImageDto.Result cropCenter(byte[] image, int width, int height, ImageDto.Format format) {
		return variants(image, List.of(ImageDto.Spec.builder().name("crop").width(width).height(height).mode(ImageDto.Mode.COVER).format(format).build()))
			.get(0);
	}

	/** 원본을 한 번만 디코딩해 여러 규격 — 프로필 사진의 썸네일·중간·큰 크기를 한 번에 */
	public static List<ImageDto.Result> variants(byte[] image, List<ImageDto.Spec> specs) {
		if (specs == null || specs.isEmpty()) {
			throw new CoreException(CommonErrorCode.BAD_REQUEST, "만들 규격이 없습니다.");
		}
		for (ImageDto.Spec spec : specs) {
			if (spec.getWidth() < 1 || spec.getHeight() < 1) {
				throw new CoreException(CommonErrorCode.BAD_REQUEST, "규격 크기는 1 이상이어야 합니다: " + spec.getName());
			}
		}
		Decoded source = decode(image, specs);
		List<ImageDto.Result> results = new ArrayList<>();
		for (ImageDto.Spec spec : specs) {
			BufferedImage output = spec.getMode() == ImageDto.Mode.COVER ? cover(source, spec.getWidth(), spec.getHeight())
				: fit(source, spec.getWidth(), spec.getHeight());
			results.add(ImageDto.Result.builder().name(spec.getName()).bytes(encode(output, spec.getFormat(), spec.getQuality()))
										.width(output.getWidth()).height(output.getHeight()).format(spec.getFormat()).build());
		}
		return results;
	}

	/** 헤더로 크기를 먼저 확인하고, 규격에 필요한 만큼만 건너뛰어 디코딩 → EXIF 방향 보정 */
	private static Decoded decode(byte[] image, List<ImageDto.Spec> specs) {
		try (ImageInputStream input = input(image)) {
			ImageReader reader = reader(input);
			try {
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				long pixels = (long) width * height;
				if (pixels > maxPixels) {
					throw new CoreException(ImageError.TOO_LARGE, "이미지 해상도가 너무 큽니다 (최대 " + maxPixels + " 픽셀).")
						.addErrorLogData("width", width).addErrorLogData("height", height);
				}
				String format = formatOf(reader);
				int orientation = "jpeg".equals(format) ? ExifOrientation.read(image) : ExifOrientation.NORMAL;
				boolean isSwapped = orientation >= 5;    // 90°·270° 계열은 바로 세우면 가로·세로가 바뀐다
				int uprightWidth = isSwapped ? height : width;
				int uprightHeight = isSwapped ? width : height;
				int step = SUBSAMPLING.contains(format) ? subsampling(uprightWidth, uprightHeight, specs) : 1;
				ImageReadParam param = null;
				if (step > 1) {
					param = reader.getDefaultReadParam();
					param.setSourceSubsampling(step, step, 0, 0);
				}
				return new Decoded(ExifOrientation.apply(reader.read(0, param), orientation), uprightWidth, uprightHeight);
			} finally {
				reader.dispose();
			}
		} catch (IOException | RuntimeException e) {
			if (e instanceof CoreException coreException) {
				throw coreException;
			}
			throw new CoreException(ImageError.INVALID_IMAGE, ImageError.INVALID_IMAGE.message(), e);
		}
	}

	/**
	 * 디코딩 간격(가로·세로 같은 값) — 가장 큰 규격도 원본의 절반 이하로 줄일 때만 건너뛰어 읽는다.
	 * 결과 품질을 위해 가장 큰 규격의 2배 해상도는 남기고, 나머지는 {@link #scale} 이 반씩 줄인다.
	 * @param width 방향 보정 후 원본 가로
	 * @param height 방향 보정 후 원본 세로
	 */
	static int subsampling(int width, int height, List<ImageDto.Spec> specs) {
		double largest = 0;
		for (ImageDto.Spec spec : specs) {
			double widthRatio = (double) spec.getWidth() / width;
			double heightRatio = (double) spec.getHeight() / height;
			largest = Math.max(largest, spec.getMode() == ImageDto.Mode.COVER ? Math.max(widthRatio, heightRatio) : Math.min(widthRatio, heightRatio));
		}
		return largest >= 0.5 ? 1 : (int) (1 / (2 * largest));
	}

	/** ImageIO.createImageInputStream 은 전역 useCache 에 따라 임시 파일을 만든다 — 메모리 캐시 스트림을 직접 쓴다 */
	private static ImageInputStream input(byte[] image) {
		if (image == null || image.length == 0) {
			throw new CoreException(ImageError.INVALID_IMAGE, "이미지가 비어 있습니다.");
		}
		return new MemoryCacheImageInputStream(new ByteArrayInputStream(image));
	}

	private static ImageReader reader(ImageInputStream input) throws IOException {
		Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
		while (readers.hasNext()) {
			ImageReader reader = readers.next();
			if (READABLE.contains(formatOf(reader))) {
				reader.setInput(input, true, true);    // 메타데이터는 읽지 않는다
				return reader;
			}
		}
		throw new CoreException(ImageError.INVALID_IMAGE, "지원하지 않는 이미지 형식입니다 (jpeg·png·gif·bmp·webp).");
	}

	private static String formatOf(ImageReader reader) throws IOException {
		String name = StringUtils.defaultIfBlank(reader.getFormatName(), "").toLowerCase(Locale.ROOT);
		return "jpg".equals(name) ? "jpeg" : name;
	}

	/** 결과 크기는 건너뛰어 읽기 전 원본 크기로 계산한다 — 서브샘플링 여부와 상관없이 같은 크기가 나온다 */
	private static BufferedImage fit(Decoded source, int maxWidth, int maxHeight) {
		double scale = Math.min(1.0, Math.min((double) maxWidth / source.width(), (double) maxHeight / source.height()));
		int width = Math.max(1, (int) Math.round(source.width() * scale));
		int height = Math.max(1, (int) Math.round(source.height() * scale));
		return scale(source.image(), width, height);
	}

	private static BufferedImage cover(Decoded source, int width, int height) {
		double scale = Math.max((double) width / source.width(), (double) height / source.height());
		if (scale > 1.0) {
			// 원본이 상자보다 작으면 확대하지 않고 가능한 만큼만 가운데에서 같은 비율로 자른다 (이때는 건너뛰어 읽지 않는다)
			BufferedImage image = source.image();
			double ratio = (double) width / height;
			int cropWidth = Math.min(image.getWidth(), (int) Math.round(image.getHeight() * ratio));
			int cropHeight = Math.min(image.getHeight(), (int) Math.round(cropWidth / ratio));
			return copy(image.getSubimage((image.getWidth() - cropWidth) / 2, (image.getHeight() - cropHeight) / 2, cropWidth, cropHeight));
		}
		BufferedImage scaled = scale(source.image(), Math.max(width, (int) Math.ceil(source.width() * scale)),
			Math.max(height, (int) Math.ceil(source.height() * scale)));
		return copy(scaled.getSubimage((scaled.getWidth() - width) / 2, (scaled.getHeight() - height) / 2, width, height));
	}

	/** 크게 줄일 때는 반씩 여러 번 — 한 번에 줄이면 계단·뭉개짐이 생긴다 */
	private static BufferedImage scale(BufferedImage source, int width, int height) {
		int type = bufferType(source);
		BufferedImage current = source;
		int currentWidth = source.getWidth();
		int currentHeight = source.getHeight();
		do {
			currentWidth = Math.max(width, currentWidth / 2);
			currentHeight = Math.max(height, currentHeight / 2);
			if (currentWidth == current.getWidth() && currentHeight == current.getHeight()) {
				break;
			}
			BufferedImage next = new BufferedImage(currentWidth, currentHeight, type);
			Graphics2D graphics = next.createGraphics();
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			graphics.drawImage(current, 0, 0, currentWidth, currentHeight, null);
			graphics.dispose();
			current = next;
		} while (currentWidth != width || currentHeight != height);
		return current;
	}

	private static BufferedImage copy(BufferedImage image) {
		BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), bufferType(image));
		Graphics2D graphics = copy.createGraphics();
		graphics.drawImage(image, 0, 0, null);
		graphics.dispose();
		return copy;
	}

	/** 알파가 없으면 RGB 버퍼 — JPEG 으로 쓸 때 흰 배경 사본을 한 번 더 만들지 않는다 */
	private static int bufferType(BufferedImage image) {
		return image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
	}

	private static byte[] encode(BufferedImage image, ImageDto.Format format, float quality) {
		// ImageIO.createImageOutputStream 도 전역 useCache 를 따른다 — 메모리 캐시 스트림을 직접 쓴다
		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
			BufferedImage target = image;
			if (format == ImageDto.Format.JPEG && image.getType() != BufferedImage.TYPE_INT_RGB) {
				// JPEG 은 투명을 못 담는다 — 흰 배경 위에 그린다 (안 하면 검게 나온다). 이미 알파 없는 RGB 버퍼면 그대로 쓴다
				target = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
				Graphics2D graphics = target.createGraphics();
				graphics.setColor(Color.WHITE);
				graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
				graphics.drawImage(image, 0, 0, null);
				graphics.dispose();
			}
			ImageWriter writer = ImageIO.getImageWritersByFormatName(format.getExtension()).next();
			try {
				writer.setOutput(output);
				ImageWriteParam param = writer.getDefaultWriteParam();
				if (format == ImageDto.Format.JPEG) {
					param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
					param.setCompressionQuality(Math.max(0f, Math.min(1f, quality)));
				}
				writer.write(null, new IIOImage(target, null, null), param);
			} finally {
				writer.dispose();
			}
			output.flush();
			return bytes.toByteArray();
		} catch (IOException e) {
			throw new CoreException(ImageError.PROCESS_FAILED, ImageError.PROCESS_FAILED.message(), e);
		}
	}

	/** 디코딩한 이미지와 방향 보정 후 원본 크기 — 건너뛰어 읽었어도 결과 크기는 원본 크기로 계산한다 */
	private record Decoded(BufferedImage image, int width, int height) {
	}
}
