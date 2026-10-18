// file 예제를 실제 앱으로 띄워 업로드→다운로드→삭제와 위험 확장자·임의 키 차단 검증
package com.corelib.example.file;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class FileExampleTest {

	@TempDir
	static Path uploadDir;

	private final MockMvc mockMvc;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("core.file.local.base-dir", () -> uploadDir.toString());
	}

	@Test
	@DisplayName("업로드 → 목록 → 다운로드(한글 파일명·attachment) → 삭제 → 404")
	void uploadDownloadDelete() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "보고서.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
		String body = mockMvc.perform(multipart("/api/files/upload").file(file))
							 .andExpect(status().isOk())
							 .andExpect(jsonPath("$.data.originalName").value("보고서.txt"))
							 .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		String key = JsonPath.read(body, "$.data.key");

		mockMvc.perform(get("/api/files/list")).andExpect(jsonPath("$.data[0].key").value(key));
		mockMvc.perform(get("/api/files/download").param("key", key))
			   .andExpect(status().isOk())
			   .andExpect(header().string("Content-Disposition", containsString("attachment")))
			   .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''")))
			   .andExpect(content().string("hello"));

		mockMvc.perform(post("/api/files/delete").contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"" + key + "\"}"))
			   .andExpect(jsonPath("$.success").value(true));
		mockMvc.perform(get("/api/files/download").param("key", key)).andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("웹셸 확장자 업로드 - 400 FILE-INVALID")
	void blocksJsp() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "shell.JSP", "text/plain", "<% %>".getBytes(StandardCharsets.UTF_8));

		mockMvc.perform(multipart("/api/files/upload").file(file))
			   .andExpect(status().isBadRequest())
			   .andExpect(jsonPath("$.code").value("FILE-INVALID"));
	}

	@Test
	@DisplayName("발급하지 않은 키(경로 조작 포함)로 다운로드 - 404")
	void unknownKey() throws Exception {
		mockMvc.perform(get("/api/files/download").param("key", "../../etc/passwd")).andExpect(status().isNotFound());
	}
}
