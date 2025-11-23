#!/bin/bash

# Quick stop script for Docker setup

set -e

echo "� Stopping Chat App with Docker..."

docker-compose stop

echo "✅ Services stopped!"
