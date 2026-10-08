#!/bin/sh
set -eu

endpoint="${RUSTFS_ENDPOINT:-http://rustfs:9000}"
bucket="${CHAT_MEDIA_S3_BUCKET:-chat-media}"
region="${CHAT_MEDIA_S3_REGION:-us-east-1}"
cors_file="${RUSTFS_CORS_FILE:-/bootstrap/rustfs-cors.json}"

aws_s3api() {
  aws --endpoint-url "${endpoint}" --region "${region}" s3api "$@"
}

echo "Waiting for RustFS API at ${endpoint}..."
until aws_s3api list-buckets >/dev/null 2>&1; do
  sleep 2
done

if aws_s3api head-bucket --bucket "${bucket}" >/dev/null 2>&1; then
  echo "Bucket ${bucket} already exists."
else
  echo "Creating bucket ${bucket}..."
  aws_s3api create-bucket --bucket "${bucket}" >/dev/null
fi

echo "Applying CORS policy from ${cors_file}..."
aws_s3api put-bucket-cors \
  --bucket "${bucket}" \
  --cors-configuration "file://${cors_file}" >/dev/null

echo "Verifying applied CORS policy..."
aws_s3api get-bucket-cors --bucket "${bucket}" >/dev/null

echo "RustFS bucket bootstrap completed."
