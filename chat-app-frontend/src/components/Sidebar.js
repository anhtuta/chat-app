import React from "react";
import "./Sidebar.css";

function Sidebar({ groups, currentChatId, onChatSelect, onCreateGroupClick }) {
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
          className={`chat-item ${currentChatId === "public" ? "active" : ""}`}
          onClick={() => onChatSelect("public")}
        >
          <div className="chat-item-name">Public Chat</div>
          <div className="chat-item-type">Everyone</div>
        </div>

        {/* Group Items */}
        {groups.map((group) => (
          <div
            key={group.id}
            className={`chat-item ${currentChatId === group.id ? "active" : ""}`}
            onClick={() => onChatSelect(group.id)}
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
