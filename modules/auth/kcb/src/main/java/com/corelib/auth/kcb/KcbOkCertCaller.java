package com.corelib.auth.kcb;

import com.corelib.common.exception.CoreException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code new kcb.module.v3.OkCert().callOkCert(target, cpCd, svcName, licensePath, requestJson)} 를 리플렉션으로 부른다.
 * <ul>
 *   <li>KCB jar 는 계약 후 회원사에만 배포돼 공개 저장소에서 받을 수 없다 — 컴파일 의존성 대신 런타임에 찾는다</li>
 *   <li>모듈이 없으면 호출 시점에 MODULE_NOT_FOUND 로 원인을 분명히 알린다</li>
 *   <li>호출은 전용 데몬 스레드({@value #POOL_SIZE}개)에서 하고 {@code auth.kcb.timeout} 까지만 기다린다 —
 *       jar 내부 통신 timeout 을 알 수 없어 요청 스레드가 무한정 묶이지 않게</li>
 *   <li>풀이 가득 차 유한 큐(크기 {@value #POOL_SIZE}×2)까지 넘치면 timeout 을 기다리지 않고 즉시 EXTERNAL_ERROR 로 거절</li>
 * </ul>
 * @implNote 호출 규격은 공개 래퍼(github.com/buYoung/KCB_OkCert_Nodejs, github.com/seungminio/okcert)의 Java 호출부 기준이다
 */
class KcbOkCertCaller {

	static final String DEFAULT_CLASS_NAME = "kcb.module.v3.OkCert";
	/** 동시 호출 상한 — 본인확인은 가입·정보 변경 때만 불려 적다. 넘치는 호출은 큐에서 기다리다 timeout 으로 끝난다 */
	static final int POOL_SIZE = 4;
	private static final String METHOD_NAME = "callOkCert";

	private final String className;
	private final Duration timeout;
	private final ExecutorService executor;
	private volatile Method method;

	KcbOkCertCaller(Duration timeout) {
		this(DEFAULT_CLASS_NAME, timeout);
	}

	/** 테스트에서 없는 클래스명을 넣어 MODULE_NOT_FOUND 경로를 확인한다 */
	KcbOkCertCaller(String className, Duration timeout) {
		this.className = className;
		this.timeout = timeout;
		// 유한 큐 + AbortPolicy — 풀이 포화되면 거절해 요청 스레드를 timeout 까지 묶지 않는다
		this.executor = new ThreadPoolExecutor(POOL_SIZE, POOL_SIZE, 60L, TimeUnit.SECONDS,
			new ArrayBlockingQueue<>(POOL_SIZE * 2), daemonThreads(), new ThreadPoolExecutor.AbortPolicy());
	}

	/**
	 * @return 모듈이 돌려준 JSON 문자열
	 * @throws CoreException MODULE_NOT_FOUND(jar 미설치) / EXTERNAL_ERROR(모듈 내부 오류·통신 실패·응답 시간 초과)
	 */
	String call(String target, String cpCd, String svcName, String licensePath, String requestJson) {
		// jar 유무는 요청 스레드에서 확인한다 — MODULE_NOT_FOUND 를 timeout 까지 기다리지 않고 바로 알린다.
		Method callMethod = method();
		Future<String> future;
		try {
			future = executor.submit(() -> invoke(callMethod, target, cpCd, svcName, licensePath, requestJson));
		} catch (RejectedExecutionException e) {
			// 풀 포화(큐 가득) 또는 종료 — 즉시 EXTERNAL_ERROR 로 돌려줘 요청 스레드가 timeout 까지 대기하지 않는다
			throw new CoreException(KcbAuthError.EXTERNAL_ERROR, "KCB 모듈 호출을 처리할 수 없습니다.")
				.addErrorLogData("svcName", svcName)
				.addErrorLogData("cause", e.getClass().getSimpleName());
		}
		try {
			return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			// 소켓 대기는 인터럽트로 풀리지 않을 수 있다 — 풀 스레드는 jar 내부 timeout 뒤에 돌아오고, 요청은 여기서 끝낸다.
			future.cancel(true);
			throw new CoreException(KcbAuthError.EXTERNAL_ERROR, "KCB 모듈 응답 시간이 초과되었습니다.")
				.addErrorLogData("svcName", svcName)
				.addErrorLogData("timeoutMs", timeout.toMillis());
		} catch (InterruptedException e) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw new CoreException(KcbAuthError.EXTERNAL_ERROR, "KCB 모듈 호출이 중단되었습니다.").addErrorLogData("svcName", svcName);
		} catch (ExecutionException e) {
			if (e.getCause() instanceof CoreException coreException) {
				throw coreException;
			}
			// 모듈이 던진 Error 등 — 메시지에 요청값이 실릴 수 있어 예외 타입만 남긴다.
			throw new CoreException(KcbAuthError.EXTERNAL_ERROR, "KCB 모듈 호출에 실패했습니다.")
				.addErrorLogData("svcName", svcName)
				.addErrorLogData("cause", e.getCause() == null ? null : e.getCause().getClass().getName());
		}
	}

	/** 컨텍스트 종료 시 {@link KcbAuthInitialize} 가 부른다 — 진행 중인 호출은 인터럽트한다 */
	void shutdown() {
		executor.shutdownNow();
	}

	private static String invoke(Method callMethod, String target, String cpCd, String svcName, String licensePath,
								 String requestJson) {
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

	/** 앱 종료를 막지 않는 데몬 스레드 — 스레드 덤프에서 알아보게 이름을 붙인다 */
	private static ThreadFactory daemonThreads() {
		AtomicInteger sequence = new AtomicInteger();
		return runnable -> {
			Thread thread = new Thread(runnable, "kcb-okcert-" + sequence.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}
}
