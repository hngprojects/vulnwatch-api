namespace Application.Features.Auth.DTOs;

public record GoogleLoginRequest(string IdToken)
{
    public static GoogleLoginRequest Create(string idToken) => new(idToken);
}
