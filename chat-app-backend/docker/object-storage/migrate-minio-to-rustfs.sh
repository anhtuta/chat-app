#!/bin/sh
set -eu

source_endpoint="${MINIO_SOURCE_ENDPOINT:-http://minio-migration-source:9000}"
source_bucket="${MINIO_SOURCE_BUCKET:-chat-media}"
source_region="${MINIO_SOURCE_REGION:-us-east-1}"
source_access_key="${MINIO_SOURCE_ACCESS_KEY:-minioadmin}"
source_secret_key="${MINIO_SOURCE_SECRET_KEY:-minioadmin}"

target_endpoint="${RUSTFS_TARGET_ENDPOINT:-http://rustfs:9000}"
target_bucket="${RUSTFS_TARGET_BUCKET:-chat-media}"
target_region="${RUSTFS_TARGET_REGION:-us-east-1}"
target_access_key="${RUSTFS_TARGET_ACCESS_KEY:-rustfslocal}"
target_secret_key="${RUSTFS_TARGET_SECRET_KEY:-rustfslocalsecret}"

config_file="/tmp/rclone.conf"

cat >"${config_file}" <<EOF
[source]
type = s3
provider = Minio
env_auth = false
access_key_id = ${source_access_key}
secret_access_key = ${source_secret_key}
endpoint = ${source_endpoint}
region = ${source_region}
force_path_style = true

[target]
type = s3
provider = Minio
env_auth = false
access_key_id = ${target_access_key}
secret_access_key = ${target_secret_key}
endpoint = ${target_endpoint}
region = ${target_region}
force_path_style = true
EOF

echo "Verifying source bucket ${source_bucket} on ${source_endpoint}..."
rclone lsf --config "${config_file}" "source:${source_bucket}" >/dev/null

echo "Ensuring target bucket ${target_bucket} exists on ${target_endpoint}..."
rclone mkdir --config "${config_file}" "target:${target_bucket}"

echo "Copying objects from ${source_bucket} to ${target_bucket}..."
rclone copy \
  --config "${config_file}" \
  --metadata \
  --transfers 8 \
  --checkers 16 \
  "source:${source_bucket}" \
  "target:${target_bucket}"

echo "Local MinIO -> RustFS migration completed."
