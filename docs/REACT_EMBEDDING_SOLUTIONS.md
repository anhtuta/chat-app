# Solutions for Embedding React.js into Spring Boot

This document outlines different approaches to integrate a React.js application with your Spring Boot chat application.

## Overview

There are several ways to embed a React.js app into Spring Boot, each with different trade-offs:

1. **Build React app and copy to static resources** (Recommended for production)
2. **Use Maven frontend plugin to build React during Maven build**
3. **Separate development, unified deployment**
4. **Proxy approach (separate servers in dev, combined in prod)**

## Solution 1: Build React App and Copy to Static Resources (Recommended)

### Overview

Build the React app separately and copy the production build files into Spring Boot's static resources directory.

### Pros

- ✅ Simple and straightforward
- ✅ Full control over React build process
- ✅ Works well with CI/CD pipelines
- ✅ No additional Maven plugins needed
- ✅ Clear separation of concerns

### Cons

- ❌ Manual copy step (can be automated)
- ❌ Two separate build processes

### Implementation Steps

#### Step 1: Create React App (outside Spring Boot project)

```bash
# In a separate directory or as a sibling to chat-app
npx create-react-app chat-app-frontend
cd chat-app-frontend
```

#### Step 2: Configure React Build Output

In your React app's `package.json`, ensure the build script outputs to a predictable location:

```json
{
  "scripts": {
    "build": "react-scripts build",
    "build:spring": "react-scripts build && npm run copy-to-spring"
  }
}
```

#### Step 3: Copy Build Files to Spring Boot

Create a script to copy the React build to Spring Boot's static directory:

**Option A: Manual Copy Script (copy-react-build.sh)**

```bash
#!/bin/bash
# Copy React build to Spring Boot static directory

REACT_BUILD_DIR="../chat-app-frontend/build"
SPRING_STATIC_DIR="./src/main/resources/static"

# Remove old static files (optional - be careful!)
# rm -rf $SPRING_STATIC_DIR/*

# Copy React build files
cp -r $REACT_BUILD_DIR/* $SPRING_STATIC_DIR/

echo "React build copied to Spring Boot static directory"
```

**Option B: Use npm script in React app**

Add to React app's `package.json`:

```json
{
  "scripts": {
    "copy-to-spring": "cp -r build/* ../chat-app/src/main/resources/static/"
  }
}
```

#### Step 4: Update Spring Boot Security Config

Update `SecurityConfig.java` to serve React routes:

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(csrf -> csrf
            .ignoringRequestMatchers("/api/**", "/ws/**")
        )
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/auth/**").permitAll()
            .requestMatchers("/api/**", "/ws/**").authenticated()
            // Allow React app static resources
            .requestMatchers("/static/**", "/*.js", "/*.css", "/*.json", "/*.ico").permitAll()
            // All other requests go to React app (SPA routing)
            .anyRequest().authenticated()
        )
        // ... rest of config
    return http.build();
}
```

#### Step 5: Add React Router Support

Create a controller to handle React Router routes:

```java
@Controller
public class ReactController {

    @GetMapping(value = {
        "/",
        "/login",
        "/register",
        "/chat",
        "/groups/**"
    })
    public String index() {
        return "forward:/index.html";
    }
}
```

#### Step 6: Build Process

```bash
# 1. Build React app
cd chat-app-frontend
npm run build

# 2. Copy to Spring Boot
cd ../chat-app
./copy-react-build.sh  # or use npm script

# 3. Build Spring Boot
mvn clean package
```

## Solution 2: Maven Frontend Plugin (Automated Build)

### Overview

Use Maven plugins to automatically build React app during Maven build process.

### Pros

- ✅ Single build command (`mvn clean package`)
- ✅ Automated integration
- ✅ Good for CI/CD

### Cons

- ❌ Requires Node.js/npm in build environment
- ❌ Slower builds
- ❌ More complex configuration

### Implementation Steps

#### Step 1: Add Maven Plugins to pom.xml

```xml
<build>
    <plugins>
        <!-- Existing plugins -->

        <!-- Frontend Maven Plugin -->
        <plugin>
            <groupId>com.github.eirslett</groupId>
            <artifactId>frontend-maven-plugin</artifactId>
            <version>1.15.0</version>
            <configuration>
                <workingDirectory>../chat-app-frontend</workingDirectory>
                <installDirectory>target</installDirectory>
            </configuration>
            <executions>
                <!-- Install Node and npm -->
                <execution>
                    <id>install node and npm</id>
                    <goals>
                        <goal>install-node-and-npm</goal>
                    </goals>
                    <configuration>
                        <nodeVersion>v20.10.0</nodeVersion>
                        <npmVersion>10.2.4</npmVersion>
                    </configuration>
                </execution>

                <!-- Install npm dependencies -->
                <execution>
                    <id>npm install</id>
                    <goals>
                        <goal>npm</goal>
                    </goals>
                    <configuration>
                        <arguments>install</arguments>
                    </configuration>
                </execution>

                <!-- Build React app -->
                <execution>
                    <id>npm run build</id>
                    <goals>
                        <goal>npm</goal>
                    </goals>
                    <configuration>
                        <arguments>run build</arguments>
                    </configuration>
                </execution>
            </executions>
        </plugin>

        <!-- Copy React build to static resources -->
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-resources-plugin</artifactId>
            <version>3.3.1</version>
            <executions>
                <execution>
                    <id>copy-react-build</id>
                    <phase>process-resources</phase>
                    <goals>
                        <goal>copy-resources</goal>
                    </goals>
                    <configuration>
                        <outputDirectory>${project.build.outputDirectory}/static</outputDirectory>
                        <resources>
                            <resource>
                                <directory>../chat-app-frontend/build</directory>
                                <filtering>false</filtering>
                            </resource>
                        </resources>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

#### Step 2: Project Structure

```
workspace/
├── chat-app/              (Spring Boot)
│   ├── pom.xml
│   └── src/
└── chat-app-frontend/     (React)
    ├── package.json
    └── build/            (generated)
```

#### Step 3: Build

```bash
# Single command builds everything
mvn clean package
```

## Solution 3: Separate Development, Unified Deployment

### Overview

Develop React and Spring Boot separately, but deploy as a single artifact.

### Pros

- ✅ Best developer experience (hot reload for both)
- ✅ Independent development
- ✅ Single deployment artifact

### Cons

- ❌ Requires coordination between teams
- ❌ More complex setup

### Implementation

#### Development Setup

**React Development Server:**

```bash
cd chat-app-frontend
npm start  # Runs on http://localhost:3000
```

**Spring Boot Development:**

```bash
cd chat-app
mvn spring-boot:run  # Runs on http://localhost:9010
```

**Proxy Configuration in React (package.json):**

```json
{
  "proxy": "http://localhost:9010"
}
```

#### Production Build

Use Solution 1 or 2 for production builds.

## Solution 4: Webpack Dev Server Proxy (Advanced)

### Overview

Use webpack dev server in development with proxy to Spring Boot API.

### Implementation

#### React App Configuration (webpack.config.js or vite.config.js)

**For Create React App (eject or use CRACO):**

```javascript
module.exports = {
  devServer: {
    proxy: {
      "/api": {
        target: "http://localhost:9010",
        changeOrigin: true,
      },
      "/ws": {
        target: "ws://localhost:9010",
        ws: true,
      },
    },
  },
};
```

**For Vite:**

```javascript
export default {
  server: {
    proxy: {
      "/api": "http://localhost:9010",
      "/ws": {
        target: "ws://localhost:9010",
        ws: true,
      },
    },
  },
};
```

## Recommended Approach

**For your chat application, I recommend Solution 1** because:

1. ✅ Simple and maintainable
2. ✅ Works well with your existing static file setup
3. ✅ Easy to integrate with your WebSocket setup
4. ✅ No additional build complexity
5. ✅ Clear separation between frontend and backend

### Quick Start with Solution 1

1. Create React app: `npx create-react-app chat-app-frontend`
2. Build React: `cd chat-app-frontend && npm run build`
3. Copy build: `cp -r build/* ../chat-app/src/main/resources/static/`
4. Update Spring Boot security config (see Solution 1, Step 4)
5. Add React Router controller (see Solution 1, Step 5)

---

## Additional Considerations

### WebSocket Configuration

Your React app will need to connect to WebSocket. Update your React WebSocket connection:

```javascript
// In your React app
const socket = new SockJS("http://localhost:9010/ws");
const stompClient = Stomp.over(socket);
```

### API Base URL

Create an environment configuration for your React app:

```javascript
// src/config.js
const API_BASE_URL = process.env.REACT_APP_API_URL || "http://localhost:9010";

export default API_BASE_URL;
```

### Environment Variables

Create `.env` files in React app:

**.env.development:**

```
REACT_APP_API_URL=http://localhost:9010
```

**.env.production:**

```
REACT_APP_API_URL=
```
