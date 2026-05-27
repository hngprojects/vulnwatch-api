using System.Security.Cryptography;
using System.Text;
using Application.Interfaces;

namespace Infrastructure.Services;

public class TokenService : ITokenService
{
    public (string RawToken, string TokenHash) Generate()
    {

        var bytes = new byte[32];
        RandomNumberGenerator.Fill(bytes);
        var rawToken = $"vulnscan-verify={Convert.ToBase64String(bytes).Replace("+", "-").Replace("/", "_").TrimEnd('=')}";

        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(rawToken));
        var tokenHash = Convert.ToBase64String(hash);

        return (rawToken, tokenHash);
    }
}