namespace Application.Interfaces;

public interface IDnsResolver
{
    Task<IReadOnlyList<string>> GetTxtRecords(string host, CancellationToken ct);
    Task<bool> CheckTxtRecord(string host, string expectedValue, CancellationToken ct);
}