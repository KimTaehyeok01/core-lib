package kcb.module.v3;

import java.util.concurrent.CountDownLatch;

public class OkCert {

	public static volatile String lastTarget;
	public static volatile String lastCpCd;
	public static volatile String lastSvcName;
	public static volatile String lastLicense;
	public static volatile String lastRequest;
	public static volatile String nextResponse;
	public static volatile RuntimeException nextError;
	/** 있으면 풀릴 때까지 응답하지 않는다 — 멈춘 KCB 흉내 */
	public static volatile CountDownLatch block;
	/** 막혀 있던 호출이 인터럽트로 풀리면 내려간다 */
	public static volatile CountDownLatch interrupted = new CountDownLatch(1);

	public static void reset() {
		CountDownLatch gate = block;
		if (gate != null) {
			gate.countDown();
		}
		lastTarget = null;
		lastCpCd = null;
		lastSvcName = null;
		lastLicense = null;
		lastRequest = null;
		nextResponse = null;
		nextError = null;
		block = null;
		interrupted = new CountDownLatch(1);
	}

	public String callOkCert(String target, String cpCd, String svcName, String license, String request) {
		lastTarget = target;
		lastCpCd = cpCd;
		lastSvcName = svcName;
		lastLicense = license;
		lastRequest = request;
		CountDownLatch gate = block;
		if (gate != null) {
			try {
				gate.await();
			} catch (InterruptedException e) {
				interrupted.countDown();
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted");
			}
		}
		if (nextError != null) {
			throw nextError;
		}
		return nextResponse;
	}
}
