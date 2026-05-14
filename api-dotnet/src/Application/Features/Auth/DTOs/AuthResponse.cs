using Domain.Entities;

namespace Application.Features.Auth.DTOs;

public record AuthResponse(string String, string Email)
{
    public static AuthResponse Create(string token, User user) => new(token, user.Email!);
}
