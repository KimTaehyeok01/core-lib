package com.corelib.example.swagger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "core.swagger.error-responses.enabled=false")
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class SwaggerErrorResponsesOffTest {

	private final MockMvc mockMvc;

	@Test
	@DisplayName("끄면 컨트롤러에 직접 적은 응답만 남는다")
	void off() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
			   .andExpect(jsonPath("$.paths['/api/products/list'].get.responses['404']").doesNotExist())
			   .andExpect(jsonPath("$.paths['/api/products/list'].get.responses['500']").doesNotExist())
			   .andExpect(jsonPath("$.paths['/api/products/create'].post.responses['401']").doesNotExist())
			   .andExpect(jsonPath("$.paths['/api/products/create'].post.responses['400'].description").value("요청 검증 실패 (C400)"));
	}
}
