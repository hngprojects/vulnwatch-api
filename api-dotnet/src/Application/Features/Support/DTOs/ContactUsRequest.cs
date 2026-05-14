namespace Application.Features.Auth.DTOs;

public record ContactUsRequest(string Name, string Email,
    string PhoneNumber,
    string RequestType,
    string Content)
{
    public static ContactUsRequest Create(string name, string email,
    string phoneNumber,
    string requestType,
    string content) => new(name, email,
    phoneNumber,
    requestType,
    content);
}
