using Domain.Entities;

namespace Application.Features.Support.DTOs;

public record ContactUsResponse(string Message)
{
    public static ContactUsResponse Create(string message) => new(message);
}
