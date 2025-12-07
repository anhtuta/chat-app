# React Frontend Setup for Chat App

This React application is embedded into the Spring Boot chat application.

## Quick Start

### Development

1. Install dependencies:

   ```bash
   npm install
   ```

2. Start the development server:

   ```bash
   npm start
   ```

   This will run on `http://localhost:3000` with hot reload.

3. For development, configure the proxy in `package.json` to point to your Spring Boot backend:
   ```json
   {
     "proxy": "http://localhost:9010"
   }
   ```

### Building for Production

To build the React app and copy it to Spring Boot:

**Option 1: Using npm script (recommended)**

```bash
npm run build:spring
```

**Option 2: Manual build and copy**

```bash
# Build React app
npm run build

# Copy to Spring Boot (using shell script)
./copy-to-spring.sh    # On Unix/Linux/Mac/Git Bash
```

**Option 3: Using npm copy script directly**

```bash
npm run build
npm run copy-to-spring
```

## Integration with Spring Boot

After building and copying, the React app will be served from:

- Spring Boot static resources: `src/main/resources/static/`
- Accessible at: `http://localhost:9010/` (or your configured port)

## WebSocket Configuration

When connecting to WebSocket in your React app, use:

```javascript
import SockJS from "sockjs-client";
import { Client } from "@stomp/stompjs";

const socket = new SockJS("/ws");
const stompClient = new Client({
  webSocketFactory: () => socket,
  // ... other config
});
```

Note: In development with proxy, use `http://localhost:9010/ws`. In production (embedded), use `/ws` (relative path).

## Environment Variables

Create `.env` files for different environments:

**.env.development:**

```
REACT_APP_API_URL=http://localhost:9010
```

**.env.production:**

```
REACT_APP_API_URL=
```

Then use in your code:

```javascript
const API_BASE_URL = process.env.REACT_APP_API_URL || "";
```

## Notes

- **Only the chat page uses React** - `login.html` and `register.html` remain as static HTML files
- The React app replaces `index.html` (the chat interface)
- **Using HashRouter** - Routes like `/#/chat`, `/#/groups/123` are handled entirely client-side
  - The hash portion (`#/chat`) is never sent to the server
  - Spring Boot only needs to serve `index.html` for `/` (which it does by default)
  - No special server-side routing configuration needed!
- Static assets (JS, CSS, images) are served directly by Spring Boot
- API calls go to `/api/**` endpoints
- WebSocket connections use `/ws/**` endpoints
- The copy scripts automatically preserve `login.html` and `register.html` during deployment
