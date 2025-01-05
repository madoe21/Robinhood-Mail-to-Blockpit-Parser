# Robinhood mail transactions to Blockpit Excel Parser

This Java project is intended to convert [Robinhood](https://join.robinhood.com/eu_crypto/martind-dc181e/?currency_pair_id=9e41c8e5-bfb5-46b5-8fa9-3a55981702d1) transactions into a [Blockpit](https://blockpit.cello.so/raqRvglmoPo) compatible format. Unfortunately, [Robinhood](https://join.robinhood.com/eu_crypto/martind-dc181e/?currency_pair_id=9e41c8e5-bfb5-46b5-8fa9-3a55981702d1) does not offer an export for the transaction history. The transaction history is also often incomplete, which is why information from the mails is also required. Unfortunately, not all transactions are sent by email (e.g. there are no emails for rewards for learning successes).

Procedure:

1. Save all [Robinhood](https://join.robinhood.com/eu_crypto/martind-dc181e/?currency_pair_id=9e41c8e5-bfb5-46b5-8fa9-3a55981702d1) mails in EML format.
2. Run this parser, which creates an Excel file.
3. Manual follow-up: Go to the [Robinhood](https://join.robinhood.com/eu_crypto/martind-dc181e/?currency_pair_id=9e41c8e5-bfb5-46b5-8fa9-3a55981702d1) app and check all transactions from the Excel file and add any missing transactions.
4. Import the Excel file into [Blockpit](https://blockpit.cello.so/raqRvglmoPo).

Note:
In the [Robinhood](https://join.robinhood.com/eu_crypto/martind-dc181e/?currency_pair_id=9e41c8e5-bfb5-46b5-8fa9-3a55981702d1) app you can find all transactions under Profile -> History. If you click on a transaction here, you may receive further information. It can also be helpful to go to the wallet for each asset and view the history there. Here, too, you may see further information if you click on the transaction.

## Usage

1. Build the project using Maven.
2. Run the application:
   ```
   mvn exec:java "-Dexec.args=example output.xlsx" "-Dfile.encoding=UTF-8"
   ```
   - `<input-folder>`: Path to the folder containing EML files.
   - `<output-file.xlsx>`: Path to the output Excel file.

## Disclaimer

- **This tool does not guarantee accuracy and is provided as-is. Use it at your own risk.**
- Cryptocurrency transactions from Robinhood may be incomplete and require manual adjustments.
- Transaction history, especially for rewards like “Gift received,” must be manually updated if necessary.

## Support

I appreciate everyone who supports me and the project! For any requests and suggestions, feel free to provide feedback.

[![Buy Me A Coffee](https://cdn.buymeacoffee.com/buttons/default-orange.png)](https://www.buymeacoffee.com/madoe21)

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for more details.
