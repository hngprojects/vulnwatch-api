namespace Application.Features.Auth.DTOs;

public record VerifyTokenRequest(string UserId, string Token)
{
    public static VerifyTokenRequest Create(string userId, string token) => new(userId, token);
}
