// NICE 통합인증 예제 API + return_url 콜백
package com.corelib.example.auth.nice.controller;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.response.ApiResponse;
import com.corelib.example.auth.nice.dto.NiceAuthExampleDto;
import com.corelib.example.auth.nice.service.NiceAuthExampleService;
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
 * 흐름: 화면 [인증하기] → POST /api/nice-auth/start → 표준창 팝업 → NICE 가 return_url(GET ?web_transaction_id=) 호출
 * → 결과 확인·회원 가입/로그인 후 /done.html 로 이동 → 팝업이 부모 창에 알리고 닫힘 → 화면이 GET /api/nice-auth/result 로 결과 표시.
 * txNo·인증 결과는 서버 세션에만 둔다 (화면·URL 로 내보내지 않는다).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class NiceAuthExampleController {

	private static final String TX_NO = "NICE_AUTH_TX_NO";
	private static final String VERIFIED = "NICE_AUTH_VERIFIED";

	private final NiceAuthExampleService niceAuthExampleService;

	@PostMapping("/api/nice-auth/start")
	public ApiResponse<NiceAuthExampleDto.StartResponse> start(HttpSession session) {
		NiceAuthExampleDto.StartResult result = niceAuthExampleService.start();
		session.setAttribute(TX_NO, result.getTxNo());
		session.removeAttribute(VERIFIED);
		return ApiResponse.success(NiceAuthExampleDto.StartResponse.builder().authUrl(result.getAuthUrl()).build());
	}

	/** auth.nice.return-url 이 가리키는 콜백 — 팝업 안에서 열린다 */
	@GetMapping("/nice-auth/return")
	public ResponseEntity<Void> callback(@RequestParam(name = "web_transaction_id", required = false) String webTransactionId,
										 HttpSession session) {
		String txNo = (String) session.getAttribute(TX_NO);
		session.removeAttribute(TX_NO);    // txNo 는 1회만 쓴다
		try {
			if (txNo == null || webTransactionId == null) {
				throw new CoreException(CommonErrorCode.BAD_REQUEST, "인증 요청 정보가 없습니다. 다시 시도해 주세요.");
			}
			session.setAttribute(VERIFIED, niceAuthExampleService.confirm(txNo, webTransactionId));
			return redirect("/done.html?result=success");
		} catch (CoreException e) {
			log.warn("NICE 본인인증 실패 - code={}", e.getErrorCode().code());
			return redirect(UriComponentsBuilder.fromPath("/done.html").queryParam("result", "fail")
												.queryParam("message", e.getMessage()).encode().build().toUriString());
		}
	}

	@GetMapping("/api/nice-auth/result")
	public ApiResponse<NiceAuthExampleDto.VerifiedMember> result(HttpSession session) {
		Object verified = session.getAttribute(VERIFIED);
		if (!(verified instanceof NiceAuthExampleDto.VerifiedMember member)) {
			throw new CoreException(CommonErrorCode.NOT_FOUND, "인증 결과가 없습니다.");
		}
		return ApiResponse.success(member);
	}

	/** 본인인증으로 가입한 회원 목록 (마스킹) */
	@GetMapping("/api/nice-auth/members/list")
	public ApiResponse<List<NiceAuthExampleDto.MemberSummary>> members() {
		return ApiResponse.success(niceAuthExampleService.members());
	}

	private static ResponseEntity<Void> redirect(String location) {
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
	}
}
