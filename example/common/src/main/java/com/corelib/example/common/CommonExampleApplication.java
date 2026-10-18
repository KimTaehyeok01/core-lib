// common 모듈 사용 예제 앱 (http://localhost:8102)
package com.corelib.example.common;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 예제 앱 진입점 */
@SpringBootApplication
public class CommonExampleApplication {

	public static void main(String[] args) {
		SpringApplication.run(CommonExampleApplication.class, args);
	}
}
