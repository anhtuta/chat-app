# React Chat App - Implementation Guide

This React application replaces the original HTML chat interface (`index.html`) with a modern React-based implementation.

## Features

✅ **HashRouter** - Client-side routing using hash (#) URLs
✅ **WebSocket Integration** - Real-time messaging with STOMP over SockJS
✅ **Public Chat** - Chat with everyone
✅ **Group Chats** - Create and join group conversations
✅ **Authentication** - Integrated with Spring Boot session
✅ **Responsive Design** - Matches original HTML design

## Key Components

### App.js

- Handles authentication check
- Sets up HashRouter
- Manages logout functionality

### ChatContainer.js

- Main state management
- WebSocket connection handling
- Message loading and sending
- Group management

### Sidebar.js

- Displays list of chats (Public + Groups)
- Handles chat selection
- Create group button

### ChatArea.js

- Displays messages
- Message input and sending
- Connection status
- Logout button

### CreateGroupModal.js

- User selection
- Group creation
- Form validation

## Services

### api.js

All REST API calls to Spring Boot backend:

- `checkAuth()` - Check authentication status
- `logout()` - Logout user
- `getGroups()` - Get user's groups
- `getPublicMessages()` - Get public chat messages
- `getGroupMessages(groupId)` - Get group messages
- `getUsers()` - Get all users (for group creation)
- `createGroup(name, participantIds)` - Create new group

### websocket.js

WebSocket/STOMP functionality:

- `connectWebSocket()` - Connect to WebSocket
- `disconnectWebSocket()` - Disconnect
- `subscribeToTopic()` - Subscribe to message topics
- `sendMessage()` - Send messages via WebSocket

## Environment Variables

Create `.env` file in `chat-app-frontend/`:

```env
# For production (embedded in Spring Boot)
REACT_APP_API_URL=
REACT_APP_WS_URL=

# For development (with proxy)
# REACT_APP_API_URL=http://localhost:9010
# REACT_APP_WS_URL=http://localhost:9010
```

## Development

1. Install dependencies:

   ```bash
   npm install
   ```

2. Start development server:

   ```bash
   npm start
   ```

   Runs on `http://localhost:3000`

3. Configure proxy in `package.json` (if needed):
   ```json
   {
     "proxy": "http://localhost:9010"
   }
   ```

## Building for Production

1. Build React app:

   ```bash
   npm run build:spring
   ```

   This builds and copies to Spring Boot static directory.

2. Build Spring Boot:

   ```bash
   cd ..
   mvn clean package
   ```

3. Run Spring Boot:
   ```bash
   mvn spring-boot:run
   ```

## Routing

The app uses HashRouter, so routes look like:

- `/#/` - Main chat (defaults to Public Chat)
- `/#/chat` - Public chat
- `/#/groups/123` - Group chat with ID 123

The hash portion is handled entirely client-side, so no server-side routing configuration is needed.

## WebSocket Topics

- `/topic/public` - Public chat messages
- `/topic/group.{groupId}` - Group chat messages

## Message Destinations

- `/app/chat.send` - Send public message
- `/app/group.send` - Send group message

## Notes

- The React app replaces `index.html` but preserves `login.html` and `register.html`
- All styling matches the original HTML design
- WebSocket connection is managed automatically
- Messages are loaded when switching chats
- Group subscriptions are managed per chat
