package com.corelib.file;

import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/** 저장소 빈이 없으면(storage=s3 인데 AWS SDK 없음 등) "미초기화" 로 알린다 */
@AutoConfiguration
public class FileStartupReport implements StartupReporter {

	private static final String NAME = "FileUtils";

	private final ObjectProvider<FileStorage> storage;
	private final ObjectProvider<FileProperty> property;

	public FileStartupReport(ObjectProvider<FileStorage> storage, ObjectProvider<FileProperty> property) {
		this.storage = storage;
		this.property = property;
	}

	@Override
	public StartupReport report() {
		FileStorage value = storage.getIfAvailable();
		FileProperty config = property.getIfAvailable();
		if (value == null || config == null) {
			return StartupReport.notReady(NAME, "저장소 없음 — core.file.storage=s3 면 software.amazon.awssdk:s3 의존성 필요");
		}
		String limit = "max-size=" + config.getMaxSize().toMegabytes() + "MB";
		if (value instanceof LocalFileStorage) {
			return StartupReport.ready(NAME, "local, base-dir=" + config.getLocal().getBaseDir() + ", " + limit);
		}
		if (config.getStorage() == FileProperty.StorageType.S3) {
			return StartupReport.ready(NAME, "s3, bucket=" + config.getS3().getBucket() + ", region=" + config.getS3().getRegion() + ", " + limit);
		}
		return StartupReport.ready(NAME, "custom(" + value.getClass().getSimpleName() + "), " + limit);
	}
}
