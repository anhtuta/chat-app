#!/bin/bash

# Quick stop script for Docker setup

set -e

echo "� Stopping Chat App with Docker..."

docker-compose down

echo "✅ Services stopped!"
