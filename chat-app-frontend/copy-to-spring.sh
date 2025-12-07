#!/bin/bash
# Copy React build to Spring Boot static directory
# This script works on Unix/Linux/Mac and Git Bash on Windows
# Preserves existing login.html and register.html files

REACT_BUILD_DIR="./build"
SPRING_STATIC_DIR="../src/main/resources/static"
BACKUP_DIR="../src/main/resources/static-backup"

# Check if build directory exists
if [ ! -d "$REACT_BUILD_DIR" ]; then
    echo "Error: Build directory not found. Please run 'npm run build' first."
    exit 1
fi

# Create static directory if it doesn't exist
mkdir -p "$SPRING_STATIC_DIR"

# Backup existing login.html and register.html if they exist
if [ -f "$SPRING_STATIC_DIR/login.html" ]; then
    echo "Backing up login.html..."
    cp "$SPRING_STATIC_DIR/login.html" "$SPRING_STATIC_DIR/login.html.backup"
fi

if [ -f "$SPRING_STATIC_DIR/register.html" ]; then
    echo "Backing up register.html..."
    cp "$SPRING_STATIC_DIR/register.html" "$SPRING_STATIC_DIR/register.html.backup"
fi

# Copy React build files (this will overwrite index.html and other React files)
echo "Copying React build files to Spring Boot static directory..."
cp -r "$REACT_BUILD_DIR"/* "$SPRING_STATIC_DIR"/

# Restore login.html and register.html from backup if they were backed up
if [ -f "$SPRING_STATIC_DIR/login.html.backup" ]; then
    echo "Restoring login.html..."
    mv "$SPRING_STATIC_DIR/login.html.backup" "$SPRING_STATIC_DIR/login.html"
fi

if [ -f "$SPRING_STATIC_DIR/register.html.backup" ]; then
    echo "Restoring register.html..."
    mv "$SPRING_STATIC_DIR/register.html.backup" "$SPRING_STATIC_DIR/register.html"
fi

echo "✅ React build copied to Spring Boot static directory successfully!"
echo "   Destination: $SPRING_STATIC_DIR"
echo "   Note: login.html and register.html have been preserved."

