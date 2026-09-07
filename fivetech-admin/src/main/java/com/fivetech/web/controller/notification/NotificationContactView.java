package com.fivetech.web.controller.notification;

/**
 * 通知页面可使用的用户联系人信息，避免返回用户敏感字段。
 */
public class NotificationContactView
{
    private final Long userId;
    private final String userName;
    private final String nickName;
    private final String email;
    private final String phonenumber;
    private final String notificationContacts;

    public NotificationContactView(Long userId, String userName, String nickName,
                                   String email, String phonenumber, String notificationContacts)
    {
        this.userId = userId;
        this.userName = userName;
        this.nickName = nickName;
        this.email = email;
        this.phonenumber = phonenumber;
        this.notificationContacts = notificationContacts;
    }

    public Long getUserId() { return userId; }
    public String getUserName() { return userName; }
    public String getNickName() { return nickName; }
    public String getEmail() { return email; }
    public String getPhonenumber() { return phonenumber; }
    public String getNotificationContacts() { return notificationContacts; }
}
