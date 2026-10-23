package com.corelib.example.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "core.image.max-pixels=5000000")
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class ProfileImageExampleTest {

	private final MockMvc mockMvc;

	private static MockMultipartFile png(int width, int height, String fileName) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(Color.ORANGE);
		graphics.fillRect(0, 0, width, height);
		graphics.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return new MockMultipartFile("file", fileName, "image/png", out.toByteArray());
	}

	@Test
	@DisplayName("업로드 — 1200×800 → 썸네일 100×100, 중간 400×267, 큰 1080×720 / 규격별 보기는 JPEG")
	void uploadAndView() throws Exception {
		String body = mockMvc.perform(multipart("/api/profile-images/upload").file(png(1200, 800, "me.png")))
							 .andExpect(status().isOk())
							 .andExpect(jsonPath("$.data.originalFormat").value("png"))
							 .andExpect(jsonPath("$.data.variants[0].name").value("thumbnail"))
							 .andExpect(jsonPath("$.data.variants[0].width").value(100))
							 .andExpect(jsonPath("$.data.variants[0].height").value(100))
							 .andExpect(jsonPath("$.data.variants[1].width").value(400))
							 .andExpect(jsonPath("$.data.variants[1].height").value(267))
							 .andExpect(jsonPath("$.data.variants[2].width").value(1080))
							 .andReturn().getResponse().getContentAsString();
		String url = JsonPath.read(body, "$.data.variants[0].url");

		byte[] thumbnail = mockMvc.perform(get(url))
								  .andExpect(header().string("Content-Type", "image/jpeg"))
								  .andExpect(header().string("X-Content-Type-Options", "nosniff"))
								  .andReturn().getResponse().getContentAsByteArray();
		assertThat(ImageIO.read(new ByteArrayInputStream(thumbnail)).getWidth()).isEqualTo(100);
	}

	@Test
	@DisplayName("이미지 아닌 파일(확장자만 .png) — IMAGE-INVALID, 해상도 상한 초과 — IMAGE-TOO-LARGE")
	void rejected() throws Exception {
		mockMvc.perform(multipart("/api/profile-images/upload").file(new MockMultipartFile("file", "fake.png", "image/png", "<?php echo 1;".getBytes())))
			   .andExpect(status().isBadRequest())
			   .andExpect(jsonPath("$.code").value("IMAGE-INVALID"));
		mockMvc.perform(multipart("/api/profile-images/upload").file(png(3000, 2000, "huge.png")))
			   .andExpect(status().isBadRequest())
			   .andExpect(jsonPath("$.code").value("IMAGE-TOO-LARGE"));
	}

	@Test
	@DisplayName("없는 사진·없는 규격 — C404")
	void notFound() throws Exception {
		mockMvc.perform(get("/api/profile-images/view").param("imageId", "nope")).andExpect(status().isNotFound());
	}
}
