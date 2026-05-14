using Domain.Entities;

namespace Application.Features.Auth.DTOs;

public record AuthResponse(string AccessToken, string RefreshToken)
{
    public static AuthResponse Create(string accessToken, string refreshToken) => new(accessToken, refreshToken);
}
