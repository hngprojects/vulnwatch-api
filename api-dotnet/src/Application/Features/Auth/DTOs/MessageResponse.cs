namespace Application.Features.Auth.DTOs;

public record MessageResponse(string Message)
{
    public static MessageResponse Create(string message) => new(message);
}
