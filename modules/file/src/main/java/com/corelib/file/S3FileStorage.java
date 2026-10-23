package com.corelib.file;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.lang.StringUtils;
import java.io.InputStream;
import java.time.Duration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

public class S3FileStorage extends AbstractFileStorage {

	private final S3Client s3Client;
	private final S3Presigner presigner;
	private final String bucket;
	private final Duration presignedUrlExpiry;

	public S3FileStorage(FileProperty property, S3Client s3Client, S3Presigner presigner) {
		super(property);
		if (StringUtils.isBlank(property.getS3().getBucket())) {
			throw new CoreException(CommonErrorCode.INTERNAL_SERVER, "S3 버킷 미설정 - core.file.s3.bucket 확인");
		}
		this.s3Client = s3Client;
		this.presigner = presigner;
		this.bucket = property.getS3().getBucket();
		this.presignedUrlExpiry = property.getS3().getPresignedUrlExpiry();
	}

	@Override
	protected void store(String key, InputStream content, long size, String contentType) {
		try {
			PutObjectRequest request = PutObjectRequest.builder()
													   .bucket(bucket)
													   .key(key)
													   .contentType(contentType)
													   .contentLength(size)
													   .build();
			s3Client.putObject(request, RequestBody.fromInputStream(content, size));
		} catch (SdkException e) {
			throw new CoreException(FileError.STORAGE_ERROR, "S3 업로드 실패 - key=" + key, e);
		}
	}

	@Override
	public InputStream download(String key) {
		validateKey(key);
		try {
			return s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
		} catch (NoSuchKeyException e) {
			throw new CoreException(FileError.NOT_FOUND, "파일 없음 - key=" + key, e);
		} catch (SdkException e) {
			throw new CoreException(FileError.STORAGE_ERROR, "S3 다운로드 실패 - key=" + key, e);
		}
	}

	@Override
	public void delete(String key) {
		validateKey(key);
		try {
			s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
		} catch (SdkException e) {
			throw new CoreException(FileError.STORAGE_ERROR, "S3 삭제 실패 - key=" + key, e);
		}
	}

	@Override
	public String getUrl(String key) {
		validateKey(key);
		GetObjectPresignRequest request = GetObjectPresignRequest.builder()
																  .signatureDuration(presignedUrlExpiry)
																  .getObjectRequest(get -> get.bucket(bucket).key(key))
																  .build();
		return presigner.presignGetObject(request).url().toString();
	}
}
