using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Infrastructure.Migrations
{
    /// <inheritdoc />
    public partial class AddFirstLastNameRepoId : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropForeignKey(
                name: "FK_Scans_MonitoredRepositories_MonitoredRepositoryId",
                table: "Scans");

            migrationBuilder.RenameColumn(
                name: "MonitoredRepositoryId",
                table: "Scans",
                newName: "RepositoryId");

            migrationBuilder.RenameIndex(
                name: "IX_Scans_MonitoredRepositoryId",
                table: "Scans",
                newName: "IX_Scans_RepositoryId");

            migrationBuilder.AddColumn<string>(
                name: "FirstName",
                table: "AspNetUsers",
                type: "character varying(100)",
                maxLength: 100,
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "LastName",
                table: "AspNetUsers",
                type: "character varying(100)",
                maxLength: 100,
                nullable: true);

            migrationBuilder.AddForeignKey(
                name: "FK_Scans_MonitoredRepositories_RepositoryId",
                table: "Scans",
                column: "RepositoryId",
                principalTable: "MonitoredRepositories",
                principalColumn: "Id",
                onDelete: ReferentialAction.Cascade);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropForeignKey(
                name: "FK_Scans_MonitoredRepositories_RepositoryId",
                table: "Scans");

            migrationBuilder.DropColumn(
                name: "FirstName",
                table: "AspNetUsers");

            migrationBuilder.DropColumn(
                name: "LastName",
                table: "AspNetUsers");

            migrationBuilder.RenameColumn(
                name: "RepositoryId",
                table: "Scans",
                newName: "MonitoredRepositoryId");

            migrationBuilder.RenameIndex(
                name: "IX_Scans_RepositoryId",
                table: "Scans",
                newName: "IX_Scans_MonitoredRepositoryId");

            migrationBuilder.AddForeignKey(
                name: "FK_Scans_MonitoredRepositories_MonitoredRepositoryId",
                table: "Scans",
                column: "MonitoredRepositoryId",
                principalTable: "MonitoredRepositories",
                principalColumn: "Id");
        }
    }
}
