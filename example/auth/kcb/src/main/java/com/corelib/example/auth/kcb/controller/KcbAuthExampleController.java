// KCB 본인확인 예제 API + return_url 콜백
package com.corelib.example.auth.kcb.controller;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.response.ApiResponse;
import com.corelib.example.auth.kcb.dto.KcbAuthExampleDto;
import com.corelib.example.auth.kcb.service.KcbAuthExampleService;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 흐름: [인증하기] → POST /api/kcb/start → 화면이 팝업에 form POST(popupUrl) → KCB 가 return_url(?mdl_tkn=) 호출
 * → 결과 확인·회원 가입/로그인 후 /done.html → 부모 창 알림 → GET /api/kcb/result.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class KcbAuthExampleController {

	private static final String VERIFIED = "KCB_AUTH_VERIFIED";

	private final KcbAuthExampleService kcbAuthExampleService;

	@PostMapping("/api/kcb/start")
	public ApiResponse<KcbAuthExampleDto.StartResponse> start(HttpSession session) {
		session.removeAttribute(VERIFIED);
		return ApiResponse.success(kcbAuthExampleService.start());
	}

	/** auth.kcb.return-url 이 가리키는 콜백 — 팝업 안에서 열린다 */
	@GetMapping("/kcb/return")
	public ResponseEntity<Void> callback(@RequestParam(name = "mdl_tkn", required = false) String mdlTkn, HttpSession session) {
		try {
			if (mdlTkn == null) {
				throw new CoreException(CommonErrorCode.BAD_REQUEST, "인증 토큰이 없습니다. 다시 시도해 주세요.");
			}
			session.setAttribute(VERIFIED, kcbAuthExampleService.confirm(mdlTkn));
			return redirect("/done.html?result=success");
		} catch (CoreException e) {
			log.warn("KCB 본인확인 실패 - code={}", e.getErrorCode().code());
			return redirect(UriComponentsBuilder.fromPath("/done.html").queryParam("result", "fail")
												.queryParam("message", e.getMessage()).encode().build().toUriString());
		}
	}

	@GetMapping("/api/kcb/result")
	public ApiResponse<KcbAuthExampleDto.VerifiedMember> result(HttpSession session) {
		Object verified = session.getAttribute(VERIFIED);
		if (!(verified instanceof KcbAuthExampleDto.VerifiedMember member)) {
			throw new CoreException(CommonErrorCode.NOT_FOUND, "인증 결과가 없습니다.");
		}
		return ApiResponse.success(member);
	}

	/** 본인확인으로 가입한 회원 목록 (마스킹) */
	@GetMapping("/api/kcb/members/list")
	public ApiResponse<List<KcbAuthExampleDto.MemberSummary>> members() {
		return ApiResponse.success(kcbAuthExampleService.members());
	}

	private static ResponseEntity<Void> redirect(String location) {
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
	}
}
