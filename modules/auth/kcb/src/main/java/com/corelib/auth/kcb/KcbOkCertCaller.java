package com.corelib.auth.kcb;

import com.corelib.common.exception.CoreException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * {@code new kcb.module.v3.OkCert().callOkCert(target, cpCd, svcName, licensePath, requestJson)} 를 리플렉션으로 부른다.
 * <ul>
 *   <li>KCB jar 는 계약 후 회원사에만 배포돼 공개 저장소에서 받을 수 없다 — 컴파일 의존성 대신 런타임에 찾는다</li>
 *   <li>모듈이 없으면 호출 시점에 MODULE_NOT_FOUND 로 원인을 분명히 알린다</li>
 * </ul>
 * @implNote 호출 규격은 공개 래퍼(github.com/buYoung/KCB_OkCert_Nodejs, github.com/seungminio/okcert)의 Java 호출부 기준이다
 */
class KcbOkCertCaller {

	static final String DEFAULT_CLASS_NAME = "kcb.module.v3.OkCert";
	private static final String METHOD_NAME = "callOkCert";

	private final String className;
	private volatile Method method;

	KcbOkCertCaller() {
		this(DEFAULT_CLASS_NAME);
	}

	/** 테스트에서 없는 클래스명을 넣어 MODULE_NOT_FOUND 경로를 확인한다 */
	KcbOkCertCaller(String className) {
		this.className = className;
	}

	/**
	 * @return 모듈이 돌려준 JSON 문자열
	 * @throws CoreException MODULE_NOT_FOUND(jar 미설치) / EXTERNAL_ERROR(모듈 내부 오류·통신 실패)
	 */
	String call(String target, String cpCd, String svcName, String licensePath, String requestJson) {
		Method callMethod = method();
		try {
			// OkCert 인스턴스의 스레드 안전성이 문서화되어 있지 않아 호출마다 새로 만든다(공개 샘플도 매번 생성).
			Object okCert = callMethod.getDeclaringClass().getDeclaredConstructor().newInstance();
			Object result = callMethod.invoke(okCert, target, cpCd, svcName, licensePath, requestJson);
			if (!(result instanceof String json)) {
				throw new CoreException(KcbAuthError.EXTERNAL_ERROR, "KCB 모듈 응답이 비어 있습니다.");
			}
			return json;
		} catch (InvocationTargetException e) {
			// 모듈 내부 예외 메시지에 요청값이 실릴 수 있어 예외 타입만 남긴다.
			throw new CoreException(KcbAuthError.EXTERNAL_ERROR, "KCB 모듈 호출에 실패했습니다.")
				.addErrorLogData("cause", e.getCause() == null ? null : e.getCause().getClass().getName());
		} catch (ReflectiveOperationException e) {
			throw new CoreException(KcbAuthError.MODULE_NOT_FOUND, "KCB 모듈을 생성할 수 없습니다.", e);
		}
	}

	private Method method() {
		Method cached = method;
		if (cached != null) {
			return cached;
		}
		try {
			ClassLoader loader = Thread.currentThread().getContextClassLoader();
			Class<?> type = Class.forName(className, true, loader != null ? loader : KcbOkCertCaller.class.getClassLoader());
			cached = type.getMethod(METHOD_NAME, String.class, String.class, String.class, String.class, String.class);
			method = cached;
			return cached;
		} catch (ClassNotFoundException | NoSuchMethodException e) {
			throw new CoreException(KcbAuthError.MODULE_NOT_FOUND,
				"KCB OkCert 모듈이 없습니다. 계약 후 받은 OkCert3 jar 를 소비 앱 classpath 에 추가하세요.", e);
		}
	}
}
