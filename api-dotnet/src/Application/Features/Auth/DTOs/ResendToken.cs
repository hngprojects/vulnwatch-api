namespace Application.Features.Auth.DTOs;

public record ResendTokenRequest(string Email)
{
    public static ResendTokenRequest Create(string email) => new(email);
}
