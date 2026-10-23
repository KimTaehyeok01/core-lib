package com.corelib.image;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/** core.image.max-pixels(기본 4천만)를 ImageUtils 에 넣는다 — 키가 필요 없어 항상 켜진다 */
@AutoConfiguration
public class ImageInitialize {

	private final long maxPixels;

	public ImageInitialize(@Value("${core.image.max-pixels:40000000}") long maxPixels) {
		if (maxPixels < 1) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "core.image.max-pixels 는 1 이상이어야 합니다.");
		}
		this.maxPixels = maxPixels;
		ImageUtils.initialize(maxPixels);
	}

	/** webp 플러그인이 실제로 잡혔는지도 알려준다 */
	@Bean
	public StartupReporter coreImageStartupReport() {
		return () -> StartupReport.ready("ImageUtils", "최대 " + maxPixels + " 픽셀, 읽기 " + ImageUtils.readableFormats() + ", 쓰기 [jpeg, png]");
	}
}
