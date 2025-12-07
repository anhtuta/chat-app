import React from "react";
import "./Sidebar.css";

function Sidebar({ groups, currentChatType, currentChatId, onChatSelect, onCreateGroupClick }) {
  return (
    <div className="sidebar">
      <div className="sidebar-header">
        <div className="sidebar-title">💬 Chats</div>
        <button className="create-group-button" onClick={onCreateGroupClick}>
          + New Group
        </button>
      </div>
      <div className="chat-list">
        {/* Public Chat Item */}
        <div
          className={`chat-item ${currentChatType === "public" ? "active" : ""}`}
          onClick={() => onChatSelect("public", null, "Public Chat")}
        >
          <div className="chat-item-name">Public Chat</div>
          <div className="chat-item-type">Everyone</div>
        </div>

        {/* Group Items */}
        {groups.map((group) => (
          <div
            key={group.id}
            className={`chat-item ${currentChatType === "group" && currentChatId === group.id ? "active" : ""}`}
            onClick={() => onChatSelect("group", group.id, group.name)}
          >
            <div className="chat-item-name">{group.name}</div>
            <div className="chat-item-type">Group</div>
          </div>
        ))}
      </div>
    </div>
  );
}

export default Sidebar;
