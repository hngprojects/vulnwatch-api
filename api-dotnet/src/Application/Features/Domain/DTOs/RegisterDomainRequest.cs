namespace Application.Features.Auth.DTOs;

public record RegisterDomainRequest(string Domain)
{
    public static RegisterDomainRequest Create(string domain) => new(domain);
}
