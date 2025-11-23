#!/bin/bash

# Quick start script for Docker setup

set -e

echo "🚀 Starting Chat App with Docker..."

# Check if .env exists
if [ ! -f .env ]; then
    echo "📝 Creating .env file from example..."
    cp .env.example .env 2>/dev/null || echo "⚠️  .env.example not found, using defaults"
fi

# Build and start services
echo "🔨 Building and starting services..."
docker-compose up -d --build

# Wait for services to be healthy
echo "⏳ Waiting for services to start..."
sleep 10

# Check service status
echo "📊 Service status:"
docker-compose ps

echo ""
echo "✅ Services started!"
echo ""
echo "📍 Access points:"
echo "   - Application: http://localhost:9010"
echo "   - RabbitMQ UI: http://localhost:15672 (guest/guest)"
echo ""
echo "📝 Useful commands:"
echo "   - View logs: docker-compose logs -f app"
echo "   - Stop: docker-compose down"
echo "   - Restart: docker-compose restart app"
echo ""

