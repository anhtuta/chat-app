import React, { useState, useEffect } from "react";
import { getUsers, createGroup } from "../services/api";
import "./CreateGroupModal.css";

function CreateGroupModal({ onClose, onGroupCreated }) {
  const [groupName, setGroupName] = useState("");
  const [users, setUsers] = useState([]);
  const [selectedUserIds, setSelectedUserIds] = useState([]);
  const [isLoading, setIsLoading] = useState(false);

  useEffect(() => {
    loadUsers();
  }, []);

  const loadUsers = async () => {
    try {
      const usersData = await getUsers();
      setUsers(usersData);
    } catch (error) {
      console.error("Error loading users:", error);
      alert("Error loading users");
    }
  };

  const toggleUser = (userId) => {
    setSelectedUserIds((prev) => {
      if (prev.includes(userId)) {
        return prev.filter((id) => id !== userId);
      } else {
        return [...prev, userId];
      }
    });
  };

  const handleSubmit = async (e) => {
    e.preventDefault();

    if (!groupName.trim()) {
      alert("Please enter a group name");
      return;
    }

    if (selectedUserIds.length === 0) {
      alert("Please select at least one participant");
      return;
    }

    setIsLoading(true);
    try {
      const newGroup = await createGroup(groupName.trim(), selectedUserIds);
      onGroupCreated(newGroup);
      onClose();
    } catch (error) {
      console.error("Error creating group:", error);
      alert("Error creating group: " + error.message);
    } finally {
      setIsLoading(false);
    }
  };

  const handleBackdropClick = (e) => {
    if (e.target === e.currentTarget) {
      onClose();
    }
  };

  return (
    <div className="modal show" onClick={handleBackdropClick}>
      <div className="modal-content" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">Create New Group</div>
        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label className="form-label" htmlFor="groupName">
              Group Name
            </label>
            <input
              type="text"
              id="groupName"
              className="form-input"
              placeholder="Enter group name"
              value={groupName}
              onChange={(e) => setGroupName(e.target.value)}
              required
            />
          </div>
          <div className="form-group">
            <label className="form-label">Select Participants</label>
            <div className="user-list">
              {users.map((user) => (
                <div
                  key={user.id}
                  className={`user-item ${selectedUserIds.includes(user.id) ? "selected" : ""}`}
                  onClick={() => toggleUser(user.id)}
                >
                  <input
                    type="checkbox"
                    checked={selectedUserIds.includes(user.id)}
                    onChange={() => toggleUser(user.id)}
                    onClick={(e) => e.stopPropagation()}
                  />
                  <div>
                    <div className="user-item-name">{user.fullname || user.username}</div>
                    <div className="user-item-username">@{user.username}</div>
                  </div>
                </div>
              ))}
            </div>
          </div>
          <div className="modal-buttons">
            <button type="button" className="btn btn-secondary" onClick={onClose} disabled={isLoading}>
              Cancel
            </button>
            <button type="submit" className="btn btn-primary" disabled={isLoading}>
              {isLoading ? "Creating..." : "Create Group"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

export default CreateGroupModal;
