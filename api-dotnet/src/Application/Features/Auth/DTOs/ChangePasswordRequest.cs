namespace Application.Features.Auth.DTOs;

public record ChangePasswordRequest(string CurrentPassword, string NewPassword, string ConfirmNewPassword)
{
    public static ChangePasswordRequest Create(string currentPassword, string newPassword, string confirmNewPassword) => new(currentPassword, newPassword, confirmNewPassword);
}
