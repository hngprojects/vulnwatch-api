namespace Application.Features.Auth.DTOs;

public record ResetPasswordRequest(string Email, string Token, string NewPassword)
{
    public static ResetPasswordRequest Create(string email, string token, string newPassword)
        => new(email, token, newPassword);
}
