namespace Application.Features.Auth.DTOs;

public record RegisterRequest(string Email, string Password, string? FirstName = null,
    string? LastName = null)
{
    public static RegisterRequest Create(string email, string password, string? firstName, string? lastName) => new(email, password, firstName, lastName);
}
