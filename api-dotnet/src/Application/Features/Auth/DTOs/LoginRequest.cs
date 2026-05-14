namespace Application.Features.Auth.DTOs;

public record LoginRequest(string Email, string Password)
{
    public static LoginRequest Create(string email, string password) => new(email, password);
}
