namespace Application.Features.Auth.DTOs;

public record ForgotPasswordRequest(string Email)
{
    public static ForgotPasswordRequest Create(string email) => new(email);
}
