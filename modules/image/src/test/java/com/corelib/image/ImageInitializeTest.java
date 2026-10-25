package com.corelib.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.exception.CoreException;
import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ImageInitializeTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ImageInitialize.class));

	@AfterEach
	void reset() {
		ImageUtils.initialize(40_000_000L);
	}

	private static byte[] png(int width, int height) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
		return out.toByteArray();
	}

	@Test
	@DisplayName("설정 없음 — 기본 4천만 픽셀, 기동 안내에 읽기 형식(webp 포함), ImageIO useCache 는 그대로")
	void defaults() {
		boolean useCache = ImageIO.getUseCache();

		runner.run(context -> {
			StartupReport report = context.getBean(StartupReporter.class).report();
			assertThat(report.isReady()).isTrue();
			assertThat(report.getDetail()).contains("최대 40000000 픽셀", "webp", "jpeg");
			assertThat(ImageIO.getUseCache()).isEqualTo(useCache);
		});
	}

	@Test
	@DisplayName("max-pixels 설정 — ImageUtils 상한에 반영돼 초과 이미지는 IMAGE-TOO-LARGE")
	void configured() throws Exception {
		byte[] image = png(400, 200);

		runner.withPropertyValues("core.image.max-pixels=1000").run(context -> {
			assertThat(context.getBean(StartupReporter.class).report().getDetail()).contains("최대 1000 픽셀");
			assertThatThrownBy(() -> ImageUtils.resize(image, 10, 10, ImageDto.Format.PNG))
				.extracting(e -> ((CoreException) e).getErrorCode()).isEqualTo(ImageError.TOO_LARGE);
		});
	}

	@Test
	@DisplayName("max-pixels 0 — 설정 오류로 기동 실패")
	void invalid() {
		runner.withPropertyValues("core.image.max-pixels=0").run(context -> assertThat(context).hasFailed());
	}
}
