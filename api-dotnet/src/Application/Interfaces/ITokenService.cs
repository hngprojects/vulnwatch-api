namespace Application.Interfaces;

public interface ITokenService
{
    (string RawToken, string TokenHash) Generate();
}